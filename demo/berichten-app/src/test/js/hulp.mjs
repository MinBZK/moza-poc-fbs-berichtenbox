// Gedeelde hulp voor de JS-tests: een nep-App Manager die de berichten echt ontsleutelt en ondertekent,
// zodat een test het hele verloop kan doorlopen zonder een draaiende App Manager. Geen .test.mjs:
// JavascriptTest draait alleen die bestanden.
import assert from 'node:assert/strict';
import { CompactEncrypt, CompactSign, compactDecrypt } from 'jose';

export const BASIS = 'https://app-manager.test/';
export const OIN = '00000000000000001100';

const encoder = new TextEncoder();
const decoder = new TextDecoder();

export function aesSleutel() {
  return crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
}

export async function versleutelDir(velden, sleutel, header = { alg: 'dir', enc: 'A256GCM', typ: 'JWE' }) {
  return new CompactEncrypt(encoder.encode(typeof velden === 'string' ? velden : JSON.stringify(velden)))
    .setProtectedHeader(header)
    .encrypt(sleutel);
}

export async function registratiebericht(velden, sleutel) {
  return `#registration_token=${await versleutelDir(velden, sleutel)}`;
}

export async function onderteken(claims, privateKey) {
  const sleutel = privateKey ?? (await maakEcdsa()).privateKey;

  return new CompactSign(encoder.encode(JSON.stringify(claims))).setProtectedHeader({ alg: 'ES256' }).sign(sleutel);
}

export function credentialMet(claims) {
  return onderteken(claims);
}

function maakEcdsa() {
  return crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign', 'verify']);
}

function header(compact) {
  return JSON.parse(Buffer.from(compact.split('.')[0], 'base64url'));
}

/** Een fetch die per pad één antwoord geeft en onthoudt wat hij kreeg. */
export function nepFetch(antwoorden) {
  const verzoeken = [];
  const fetch = async (adres, opties) => {
    verzoeken.push({ adres: String(adres), opties });

    const [status, tekst] = antwoorden[new URL(adres).pathname] ?? [404, ''];

    return new Response(tekst, { status });
  };

  return { fetch, verzoeken };
}

/** Zet een registratie in de kluis zoals een afgerond activeren dat doet. */
export async function registreer(kluis, token) {
  await kluis.bewaar(kluis.ACTIVATIE, { sessieToken: token });
  assert.equal(await kluis.vervangRegistratie(token, token), true);
}

/**
 * Een App Manager in het klein. Elke challenge krijgt een eigen tijdelijke sleutel; `complete`
 * ontsleutelt de presentatie, eist dezelfde header als de echte App Manager en geeft per sessie een
 * eigen token (`sessie-1`, `sessie-2`, …); `credential` eist een bekend sessietoken en geeft
 * [credential] versleuteld terug. [status] zet per pad een foutstatus.
 */
export function nepAppManager({ credential = 'nog geen credential', status = {} } = {}) {
  const sessies = new Map();
  const tijdelijk = new Map();
  const log = { presentaties: [], credentialVerzoeken: [] };

  const fetch = async (adres, opties = {}) => {
    const pad = new URL(adres).pathname;

    if (status[pad]) return new Response('', { status: status[pad] });

    if (pad === '/v1/challenge') {
      const paar = await crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, ['deriveBits']);
      const nonce = `nonce-${tijdelijk.size}`;
      const spki = Buffer.from(await crypto.subtle.exportKey('spki', paar.publicKey)).toString('base64');

      tijdelijk.set(nonce, paar.privateKey);

      return new Response(await onderteken({ sub: 'challenge', nonce, ephemeral_organization_ec_public_key: spki }));
    }

    if (pad === '/v1/complete') {
      const { nonce, encrypted_challenge_result: jwe } = JSON.parse(opties.body);

      assert.deepEqual({ ...header(jwe), epk: undefined }, { alg: 'ECDH-ES', enc: 'A256GCM', typ: 'JWE', epk: undefined });

      const { plaintext } = await compactDecrypt(jwe, tijdelijk.get(nonce));
      const presentatie = JSON.parse(decoder.decode(plaintext));
      const ruw = Buffer.from(presentatie.session_aes_key, 'base64');
      const sleutel = await crypto.subtle.importKey('raw', ruw, 'AES-GCM', false, ['encrypt', 'decrypt']);

      const sessieToken = `sessie-${sessies.size + 1}`;

      log.presentaties.push(presentatie);
      sessies.set(sessieToken, sleutel);

      return new Response(await versleutelDir({ session_token: sessieToken, expires_in: 600 }, sleutel));
    }

    if (pad === '/credential') {
      const sleutel = sessies.get(opties.headers?.Authorization);

      if (!sleutel) return new Response('', { status: 401 });

      assert.deepEqual(header(opties.body), { alg: 'dir', enc: 'A256GCM', typ: 'JWE' });

      const { plaintext } = await compactDecrypt(opties.body, sleutel);

      log.credentialVerzoeken.push(JSON.parse(decoder.decode(plaintext)));

      return new Response(await versleutelDir(credential, sleutel));
    }

    return new Response('', { status: 404 });
  };

  return { fetch, log, sessieSleutel: (token = `sessie-${sessies.size}`) => sessies.get(token) };
}
