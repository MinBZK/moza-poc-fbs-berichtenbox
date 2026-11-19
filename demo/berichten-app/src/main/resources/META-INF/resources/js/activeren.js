// De app-kant van CreateSession (FSC voor Burgers 4.3.4): met een self-signed certificaat bij de
// App Manager (activeren, 4.4.1) en met een credential bij een magazijn. Zonder DOM en zonder
// opslag: wie aanroept, geeft sleutels en fetch mee, zodat dezelfde code in de browser en in de
// tests onder Node draait.
import { CompactEncrypt, CompactSign, compactDecrypt, decodeJwt } from '../vendor/jose/index.js';

/** Vaste `iss` van de app in het verzoek om een credential (4.4.1, stap 19). */
export const APP_ISS = 'bk-connect://app/';

const AES_BYTES = 32;
const encoder = new TextEncoder();
const decoder = new TextDecoder();

/** Een afwijzing of onverwacht antwoord van de App Manager of een magazijn, met status en stap. */
export class ActiverenFout extends Error {
  constructor(melding, status, stap, opties) {
    super(melding, opties);
    this.name = 'ActiverenFout';
    this.status = status;
    this.stap = stap;
  }
}

/**
 * De App Manager of een magazijn was niet te bereiken; de oorzaak (een TypeError van fetch) staat
 * in `cause`. Een CORS-weigering ziet de browser hetzelfde, dus ook een verkeerd ingestelde dienst
 * komt hier.
 */
export class VerbindingFout extends Error {
  name = 'VerbindingFout';
}

/**
 * Opent een sessie bij de App Manager met een self-signed certificaat: de App Manager leert de
 * public key van de app en ziet dat de app de private key bezit. Het antwoord noemt het token
 * `session_token`, zoals 4.4.1.
 */
export async function maakSessie({ basis, oin, sleutelpaar, fetch = globalThis.fetch }) {
  const jwk = await publiekeJwk(sleutelpaar.publicKey);
  const certificaat = await onderteken({ sub: 'certificate_type_self_signed', cnf: { jwk } }, sleutelpaar.privateKey);

  return openSessie({
    challengeAdres: new URL('v1/challenge', basis),
    sessieAdres: new URL('v1/complete', basis),
    tokenVeld: 'session_token',
    oin,
    sleutelpaar,
    soort: 'certificate_type_self_signed',
    certificaat,
    fetch,
  });
}

/**
 * Opent een magazijnsessie met het credential van de App Manager. [basis] is het API-adres van het
 * magazijn, eindigend op `/` (`<magazijn_url>/api/v1/`). Het antwoord is camelCase (`sessieToken`).
 */
export function maakMagazijnsessie({ basis, oin, sleutelpaar, credential, fetch = globalThis.fetch }) {
  return openSessie({
    challengeAdres: new URL('rechtstreeks/challenges', basis),
    sessieAdres: new URL('rechtstreeks/sessies', basis),
    tokenVeld: 'sessieToken',
    oin,
    sleutelpaar,
    soort: 'app_manager_jwt_certificate',
    certificaat: credential,
    fetch,
  });
}

/**
 * Stap 1 tot en met 26 van `CreateSession`. Geeft het sessietoken en de AES-sleutel van de sessie
 * terug. Die sleutel is niet te exporteren; de ruwe bytes gaan alleen versleuteld in de
 * presentatie mee, naar de tijdelijke sleutel van de Verifier. De stap in een fout is het laatste
 * segment van het pad.
 */
async function openSessie({ challengeAdres, sessieAdres, tokenVeld, oin, sleutelpaar, soort, certificaat, fetch }) {
  const challengeStap = laatsteSegment(challengeAdres);
  const sessieStap = laatsteSegment(sessieAdres);
  const challenge = await tekstVan(await verbind(fetch, challengeAdres, { method: 'POST' }), challengeStap);
  // TODO(#1216): de handtekening toetsen aan de sleutel van de Verifier uit het stelseldocument;
  // tot dan rust de herkomst van de challenge op TLS.
  const claims = decodeJwt(challenge);

  if (claims.sub !== 'challenge' || typeof claims.nonce !== 'string' || typeof claims.ephemeral_organization_ec_public_key !== 'string') {
    throw new ActiverenFout('onverwachte challenge', undefined, challengeStap);
  }

  // Een sleutel die niet te lezen is, ligt aan de Verifier, niet aan de app.
  let tijdelijk;

  try {
    tijdelijk = await crypto.subtle.importKey(
      'spki', base64(claims.ephemeral_organization_ec_public_key), { name: 'ECDH', namedCurve: 'P-256' }, true, [],
    );
  } catch (fout) {
    throw new ActiverenFout('onbruikbare tijdelijke sleutel in de challenge', undefined, challengeStap, { cause: fout });
  }

  const ruw = crypto.getRandomValues(new Uint8Array(AES_BYTES));
  const aesSleutel = await crypto.subtle.importKey('raw', ruw, { name: 'AES-GCM' }, false, ['encrypt', 'decrypt']);

  const presentatie = JSON.stringify({
    app_nonce_signature: await onderteken({ sub: 'challenge_response', aud: oin, nonce: claims.nonce }, sleutelpaar.privateKey),
    certificate_type: soort,
    certificate: certificaat,
    session_aes_key: base64Tekst(ruw),
  });

  ruw.fill(0);

  const versleuteld = await new CompactEncrypt(encoder.encode(presentatie))
    .setProtectedHeader({ alg: 'ECDH-ES', enc: 'A256GCM', typ: 'JWE' })
    .encrypt(tijdelijk);

  const antwoord = await tekstVan(await verbind(fetch, sessieAdres, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ nonce: claims.nonce, encrypted_challenge_result: versleuteld }),
  }), sessieStap);

  const sessieToken = leesJson(await ontsleutel(antwoord, aesSleutel), sessieStap)?.[tokenVeld];

  if (typeof sessieToken !== 'string') throw new ActiverenFout('geen sessietoken in het antwoord', undefined, sessieStap);

  return { sessieToken, aesSleutel };
}

/**
 * Een versleuteld antwoord binnen een magazijnsessie, ontsleuteld en gelezen als JSON. Het token
 * gaat als `Bearer` mee (RFC 6750); bij de App Manager gaat het zonder schema (4.4.1).
 */
export async function haalVersleuteld({ adres, sessieToken, aesSleutel, fetch = globalThis.fetch }) {
  const url = new URL(adres);
  const antwoord = await tekstVan(await verbind(fetch, url, { headers: { Authorization: `Bearer ${sessieToken}` } }), laatsteSegment(url));

  return leesJson(await ontsleutel(antwoord, aesSleutel), laatsteSegment(url));
}

/** Waar de gebruiker heen gaat om een persona te kiezen (4.4.1, stap 7). */
export function registratieAdres(basis, sessieToken) {
  const adres = new URL('register_app', basis);

  adres.searchParams.set('session_token', sessieToken);

  return adres.href;
}

/**
 * Het `registration_token` uit het fragment van de terug-URL. Het bericht moet van `issuer` komen:
 * een bericht van een ander adres is niet van onze App Manager. `issuer` is het adres van de App
 * Manager uit de configuratie; dat moet gelijk zijn aan `app-manager.issuer-url` van de App Manager.
 */
export async function leesRegistratie(fragment, aesSleutel, issuer) {
  const jwe = new URLSearchParams(fragment.replace(/^#/, '')).get('registration_token');

  if (!jwe) throw new ActiverenFout('geen registration_token in het fragment');

  const bericht = JSON.parse(await ontsleutel(jwe, aesSleutel));

  if (bericht?.sub !== 'registration_token' || typeof bericht.registration_token !== 'string') {
    throw new ActiverenFout('onverwacht registratiebericht');
  }

  // Het bericht is al met onze eigen sessiesleutel ontsleuteld, dus een andere iss is een verschil in
  // instellingen en geen aanval of storing. De waarden staan in de melding, die alleen naar de console
  // gaat: anders is het verschil niet te vinden.
  if (bericht.iss !== issuer) throw new ActiverenFout(`registratiebericht van ${bericht.iss}, verwacht ${issuer}`, undefined, 'instelling');

  return bericht.registration_token;
}

/** Een vers credential voor de sleutel van deze sessie (4.4.1, stap 19 tot en met 28). */
export async function haalCredential({ basis, sessieToken, aesSleutel, registratieToken, fetch = globalThis.fetch }) {
  const verzoek = await new CompactEncrypt(encoder.encode(JSON.stringify({
    sub: 'registration_token',
    iss: APP_ISS,
    registration_token: registratieToken,
  })))
    .setProtectedHeader({ alg: 'dir', enc: 'A256GCM', typ: 'JWE' })
    .encrypt(aesSleutel);

  const antwoord = await tekstVan(await verbind(fetch, new URL('credential', basis), {
    method: 'POST',
    headers: { Authorization: sessieToken, 'Content-Type': 'application/jose' },
    body: verzoek,
  }), 'credential');

  return ontsleutel(antwoord, aesSleutel);
}

/**
 * Wat de app van een credential laat zien; gooit als het geen bruikbaar credential is. De
 * handtekening toetst het magazijn.
 */
export function leesCredential(credential) {
  const { ontvanger, exp } = decodeJwt(credential);

  if (typeof ontvanger !== 'string' || !Number.isFinite(exp)) throw new ActiverenFout('onbruikbaar credential');

  return { ontvanger, verlooptOp: new Date(exp * 1000) };
}

/** Alleen de verplichte leden: WebCrypto zet er ook `ext` en `key_ops` in. */
export async function publiekeJwk(publicKey) {
  const { kty, crv, x, y } = await crypto.subtle.exportKey('jwk', publicKey);

  return { kty, crv, x, y };
}

function onderteken(claims, privateKey) {
  return new CompactSign(encoder.encode(JSON.stringify(claims)))
    .setProtectedHeader({ alg: 'ES256', typ: 'jwt' })
    .sign(privateKey);
}

async function ontsleutel(jwe, aesSleutel) {
  const { plaintext } = await compactDecrypt(jwe, aesSleutel, {
    keyManagementAlgorithms: ['dir'],
    contentEncryptionAlgorithms: ['A256GCM'],
  });

  return decoder.decode(plaintext);
}

async function verbind(fetch, adres, opties) {
  try {
    return await fetch(adres, opties);
  } catch (fout) {
    throw new VerbindingFout(`${adres.pathname} niet bereikbaar`, { cause: fout });
  }
}

async function tekstVan(antwoord, stap) {
  if (!antwoord.ok) throw new ActiverenFout(`${stap} mislukt (HTTP ${antwoord.status})`, antwoord.status, stap);

  try {
    return await antwoord.text();
  } catch (fout) {
    // Het antwoord brak halverwege af: dat is de verbinding, niet de inhoud.
    throw new VerbindingFout(`${stap}: antwoord afgebroken`, { cause: fout });
  }
}

/**
 * Zonder de SyntaxError als oorzaak: die neemt een stuk van de invoer in zijn melding op, en hier is
 * dat ontsleutelde inhoud die anders in de console belandt.
 */
function leesJson(tekst, stap) {
  try {
    return JSON.parse(tekst);
  } catch {
    throw new ActiverenFout('antwoord is geen JSON', undefined, stap);
  }
}

function laatsteSegment(url) {
  return url.pathname.split('/').pop();
}

function base64(tekst) {
  return Uint8Array.from(atob(tekst), (teken) => teken.charCodeAt(0));
}

function base64Tekst(bytes) {
  return btoa(String.fromCharCode(...bytes));
}
