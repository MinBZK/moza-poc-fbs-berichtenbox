// De app-kant van CreateSession (FSC voor Burgers 4.3.4): met een self-signed certificaat bij de
// App Manager (activeren, 4.4.1) en met een credential bij een magazijn. Zonder DOM en zonder opslag: wie aanroept, geeft
// sleutels en fetch mee, zodat dezelfde code in de browser en in de tests onder Node draait.
import { CompactEncrypt, CompactSign, compactDecrypt, decodeJwt } from '../vendor/jose-6.2.13/index.js';

/** Vaste `iss` van de app in het verzoek om een credential (4.4.1, stap 19). */
export const APP_ISS = 'bk-connect://app/';

const AES_BYTES = 32;
const encoder = new TextEncoder();
const decoder = new TextDecoder();

export class ActiverenFout extends Error {
  constructor(melding, status, stap) {
    super(melding);
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
 * public key van de app en ziet dat de app de private key bezit.
 */
export async function maakSessie({ basis, oin, sleutelpaar, fetch = globalThis.fetch }) {
  const jwk = await publiekeJwk(sleutelpaar.publicKey);
  const certificaat = await teken({ sub: 'certificate_type_self_signed', cnf: { jwk } }, sleutelpaar.privateKey);

  return openSessie({
    challenge: new URL('v1/challenge', basis),
    complete: new URL('v1/complete', basis),
    oin,
    sleutelpaar,
    soort: 'certificate_type_self_signed',
    certificaat,
    fetch,
  });
}

/**
 * Opent een magazijnsessie met het credential van de App Manager. [basis] is het API-adres van het
 * magazijn, eindigend op `/` (`<magazijn_url>/api/v1/`).
 */
export function maakMagazijnsessie({ basis, oin, sleutelpaar, credential, fetch = globalThis.fetch }) {
  return openSessie({
    challenge: new URL('rechtstreeks/challenge', basis),
    complete: new URL('rechtstreeks/complete', basis),
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
 * presentatie mee, naar de tijdelijke sleutel van de Verifier.
 */
async function openSessie({ challenge: challengeUrl, complete: completeUrl, oin, sleutelpaar, soort, certificaat, fetch }) {
  const challenge = await tekstVan(await verbind(fetch, challengeUrl, { method: 'POST' }), 'challenge');
  // TODO(#1216): de handtekening toetsen aan de sleutel van de Verifier uit het stelseldocument;
  // tot dan rust de herkomst van de challenge op TLS.
  const claims = decodeJwt(challenge);

  if (claims.sub !== 'challenge' || typeof claims.nonce !== 'string' || typeof claims.ephemeral_organization_ec_public_key !== 'string') {
    throw new ActiverenFout('onverwachte challenge');
  }

  const tijdelijk = await crypto.subtle.importKey(
    'spki', base64(claims.ephemeral_organization_ec_public_key), { name: 'ECDH', namedCurve: 'P-256' }, true, [],
  );
  const ruw = crypto.getRandomValues(new Uint8Array(AES_BYTES));
  const aesSleutel = await crypto.subtle.importKey('raw', ruw, { name: 'AES-GCM' }, false, ['encrypt', 'decrypt']);

  const presentatie = JSON.stringify({
    app_nonce_signature: await teken({ sub: 'challenge_response', aud: oin, nonce: claims.nonce }, sleutelpaar.privateKey),
    certificate_type: soort,
    certificate: certificaat,
    session_aes_key: base64Tekst(ruw),
  });

  ruw.fill(0);

  const versleuteld = await new CompactEncrypt(encoder.encode(presentatie))
    .setProtectedHeader({ alg: 'ECDH-ES', enc: 'A256GCM' })
    .encrypt(tijdelijk);

  const antwoord = await tekstVan(await verbind(fetch, completeUrl, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ nonce: claims.nonce, encrypted_challenge_result: versleuteld }),
  }), 'complete');

  const { session_token: sessieToken } = JSON.parse(await ontsleutel(antwoord, aesSleutel));

  if (typeof sessieToken !== 'string') throw new ActiverenFout('geen sessietoken in het antwoord');

  return { sessieToken, aesSleutel };
}

/** Een versleuteld antwoord binnen een sessie, ontsleuteld en gelezen als JSON. */
export async function haalVersleuteld({ adres, sessieToken, aesSleutel, fetch = globalThis.fetch }) {
  const url = new URL(adres);
  const antwoord = await tekstVan(await verbind(fetch, url, { headers: { Authorization: sessieToken } }), url.pathname);

  return JSON.parse(await ontsleutel(antwoord, aesSleutel));
}

/** Waar de gebruiker heen gaat om een persona te kiezen (4.4.1, stap 7). */
export function registratieAdres(basis, sessieToken) {
  const adres = new URL('register_app', basis);

  adres.searchParams.set('session_token', sessieToken);

  return adres.href;
}

/**
 * Het `registration_token` uit het fragment van de terug-URL. Het bericht moet van [issuer] komen:
 * een bericht van een ander adres is niet van onze App Manager.
 */
export async function leesRegistratie(fragment, aesSleutel, issuer) {
  const jwe = new URLSearchParams(fragment.replace(/^#/, '')).get('registration_token');

  if (!jwe) throw new ActiverenFout('geen registration_token in het fragment');

  const bericht = JSON.parse(await ontsleutel(jwe, aesSleutel));

  if (bericht.sub !== 'registration_token' || bericht.iss !== issuer || typeof bericht.registration_token !== 'string') {
    throw new ActiverenFout('onverwacht registratiebericht');
  }

  return bericht.registration_token;
}

/** Een vers credential voor de sleutel van deze sessie (4.4.1, stap 19 tot en met 28). */
export async function haalCredential({ basis, sessieToken, aesSleutel, registratieToken, fetch = globalThis.fetch }) {
  const verzoek = await new CompactEncrypt(encoder.encode(JSON.stringify({
    sub: 'registration_token',
    iss: APP_ISS,
    registration_token: registratieToken,
  })))
    .setProtectedHeader({ alg: 'dir', enc: 'A256GCM' })
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

function teken(claims, privateKey) {
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

  return antwoord.text();
}

function base64(tekst) {
  return Uint8Array.from(atob(tekst), (teken) => teken.charCodeAt(0));
}

function base64Tekst(bytes) {
  return btoa(String.fromCharCode(...bytes));
}
