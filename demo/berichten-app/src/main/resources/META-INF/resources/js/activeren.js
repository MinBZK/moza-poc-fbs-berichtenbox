// De app-kant van CreateSession met een self-signed certificaat en van het activeren bij de App
// Manager (FSC voor Burgers 4.3.4 en 4.4.1). Zonder DOM en zonder opslag: wie aanroept, geeft
// sleutels en fetch mee, zodat dezelfde code in de browser en in de tests onder Node draait.
import { CompactEncrypt, CompactSign, compactDecrypt, decodeJwt } from '../vendor/jose-6.2.13/index.js';

/** Vaste `iss` van de app in het verzoek om een credential (4.4.1, stap 19). */
export const APP_ISS = 'bk-connect://app/';

const AES_BYTES = 32;
const encoder = new TextEncoder();
const decoder = new TextDecoder();

export class ActiverenFout extends Error {
  constructor(melding, status) {
    super(melding);
    this.name = 'ActiverenFout';
    this.status = status;
  }
}

/**
 * Opent een sessie met een self-signed certificaat: de Verifier leert de public key van de app en
 * ziet dat de app de private key bezit. Geeft het sessietoken en de AES-sleutel van de sessie
 * terug. Die sleutel is niet te exporteren; de ruwe bytes gaan alleen versleuteld in de
 * presentatie mee.
 */
export async function maakSessie({ basis, oin, sleutelpaar, fetch = globalThis.fetch }) {
  const challenge = await tekstVan(await fetch(new URL('v1/challenge', basis), { method: 'POST' }), 'challenge');
  // TODO(#1216): de handtekening toetsen aan de sleutel van de App Manager uit het stelseldocument;
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
  const jwk = await publiekeJwk(sleutelpaar.publicKey);

  const presentatie = JSON.stringify({
    app_nonce_signature: await teken({ sub: 'challenge_response', aud: oin, nonce: claims.nonce }, sleutelpaar.privateKey),
    certificate_type: 'certificate_type_self_signed',
    certificate: await teken({ sub: 'certificate_type_self_signed', cnf: { jwk } }, sleutelpaar.privateKey),
    session_aes_key: base64Tekst(ruw),
  });

  ruw.fill(0);

  const versleuteld = await new CompactEncrypt(encoder.encode(presentatie))
    .setProtectedHeader({ alg: 'ECDH-ES', enc: 'A256GCM' })
    .encrypt(tijdelijk);

  const antwoord = await tekstVan(await fetch(new URL('v1/complete', basis), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ nonce: claims.nonce, encrypted_challenge_result: versleuteld }),
  }), 'complete');

  const { session_token: sessieToken } = JSON.parse(await ontsleutel(antwoord, aesSleutel));

  if (typeof sessieToken !== 'string') throw new ActiverenFout('geen sessietoken in het antwoord');

  return { sessieToken, aesSleutel };
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

  const antwoord = await tekstVan(await fetch(new URL('credential', basis), {
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

async function tekstVan(antwoord, stap) {
  if (!antwoord.ok) throw new ActiverenFout(`${stap} mislukt (HTTP ${antwoord.status})`, antwoord.status);

  return antwoord.text();
}

function base64(tekst) {
  return Uint8Array.from(atob(tekst), (teken) => teken.charCodeAt(0));
}

function base64Tekst(bytes) {
  return btoa(String.fromCharCode(...bytes));
}
