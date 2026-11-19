'use strict';
// De app-kant van CreateSession (FSC voor Burgers 4.3.4.2) voor de Bruno-requests onder
// rechtstreeks/. Op node:crypto en niet op jose: de developer-sandbox van Bruno laadt een npm-module
// als CommonJS, en jose 6 is alleen ESM. Zo is dit ook een tweede implementatie naast de Berichten-app
// (jose) en de Verifier (jose4j), zoals TestApp in fbs-rechtstreeks dat in Kotlin is.
//
// Alleen ES256, ECDH-ES met A256GCM en dir met A256GCM: precies wat het magazijn accepteert.
const crypto = require('node:crypto');

const SCOPE = 'nl.rijksoverheid.moz.fbs.berichten';

/**
 * De App Manager die alleen op een ontwikkelmachine bestaat (`maak-sleutel.mjs`). Een eigen OIN en
 * kid, los van de demo-App Manager (`00000000000000001100`): een simulator die deze sleutel
 * vertrouwt, neemt zo geen credential aan dat zich als dat van de demo voordoet.
 */
const LOKALE_APP_MANAGER = Object.freeze({ oin: '00000000000000001199', kid: 'bruno-lokaal' });
const AES_BYTES = 32;
const IV_BYTES = 12;
const TAG_BYTES = 16;
const CREDENTIAL_SECONDEN = 3600;

const b64url = (bytes) => Buffer.from(bytes).toString('base64url');
const deel = (object) => b64url(Buffer.from(JSON.stringify(object), 'utf8'));

function sleutelpaar() {
  return crypto.generateKeyPairSync('ec', { namedCurve: 'P-256' });
}

/** Alleen de verplichte leden; een `d` mag nooit mee. */
function publiekeJwk(publicKey) {
  const { kty, crv, x, y } = publicKey.export({ format: 'jwk' });

  return { kty, crv, x, y };
}

function es256(claims, privateKey, kid) {
  const header = { alg: 'ES256', typ: 'jwt', ...(kid ? { kid } : {}) };
  const invoer = `${deel(header)}.${deel(claims)}`;
  const handtekening = crypto.sign('sha256', Buffer.from(invoer, 'ascii'), { key: privateKey, dsaEncoding: 'ieee-p1363' });

  return `${invoer}.${b64url(handtekening)}`;
}

/** Header en payload van een compacte JWS, zonder de handtekening te controleren. */
function leesJws(compact) {
  const delen = String(compact).split('.');

  if (delen.length !== 3) throw new Error('geen compacte JWS (drie delen)');

  return { header: leesDeel(delen[0]), payload: leesDeel(delen[1]) };
}

/** Concat KDF (NIST SP 800-56A) zoals RFC 7518 §4.6.2 hem invult: één ronde SHA-256, zonder apu/apv. */
function concatKdf(z, algoritme, bits) {
  const id = Buffer.from(algoritme, 'ascii');
  const lengte = (n) => { const b = Buffer.alloc(4); b.writeUInt32BE(n); return b; };

  return crypto.createHash('sha256')
    .update(lengte(1)).update(z)
    .update(lengte(id.length)).update(id)
    .update(lengte(0)).update(lengte(0))
    .update(lengte(bits))
    .digest()
    .subarray(0, bits / 8);
}

/** JWE met ECDH-ES en A256GCM naar [ontvanger], met `typ` `JWE` en verder alleen `epk`. */
function jweEcdhEs(payload, ontvanger) {
  const tijdelijk = sleutelpaar();
  const z = crypto.diffieHellman({ privateKey: tijdelijk.privateKey, publicKey: ontvanger });
  const header = deel({ alg: 'ECDH-ES', enc: 'A256GCM', typ: 'JWE', epk: publiekeJwk(tijdelijk.publicKey) });
  const sleutel = concatKdf(z, 'A256GCM', 256);

  z.fill(0);

  try {
    return aesGcm(header, payload, sleutel);
  } finally {
    sleutel.fill(0);
  }
}

function jweDir(payload, sleutel) {
  return aesGcm(deel({ alg: 'dir', enc: 'A256GCM', typ: 'JWE' }), payload, sleutel);
}

function aesGcm(header, payload, sleutel) {
  const iv = crypto.randomBytes(IV_BYTES);
  const cipher = crypto.createCipheriv('aes-256-gcm', sleutel, iv, { authTagLength: TAG_BYTES });

  cipher.setAAD(Buffer.from(header, 'ascii'));

  const versleuteld = Buffer.concat([cipher.update(payload, 'utf8'), cipher.final()]);

  return [header, '', b64url(iv), b64url(versleuteld), b64url(cipher.getAuthTag())].join('.');
}

/**
 * Een antwoord van het magazijn: JWE met `dir` en A256GCM, met precies `alg`, `enc` en `typ` `JWE`.
 * Strenger dan het contract, dat ook `kid` en `cty` toelaat, en dan de Berichten-app: deze requests
 * zijn er om te laten zien wat het magazijn stuurt, dus elke afwijking van wat het nu stuurt, valt op.
 */
function ontsleutelDir(compact, sleutel) {
  const delen = String(compact).split('.');

  if (delen.length !== 5 || delen[1] !== '') throw new Error('geen compacte JWE met dir (vijf delen, lege sleutel)');

  const header = leesDeel(delen[0]);
  const velden = Object.keys(header).sort().join(',');

  if (velden !== 'alg,enc,typ' || header.alg !== 'dir' || header.enc !== 'A256GCM' || header.typ !== 'JWE') {
    throw new Error(`onverwachte JWE-header: ${JSON.stringify(header)}`);
  }

  const iv = Buffer.from(delen[2], 'base64url');
  const tag = Buffer.from(delen[4], 'base64url');

  if (iv.length !== IV_BYTES || tag.length !== TAG_BYTES) throw new Error('IV of tag heeft de verkeerde lengte');

  const decipher = crypto.createDecipheriv('aes-256-gcm', sleutel, iv, { authTagLength: TAG_BYTES });

  decipher.setAAD(Buffer.from(delen[0], 'ascii'));
  decipher.setAuthTag(tag);

  return Buffer.concat([decipher.update(Buffer.from(delen[3], 'base64url')), decipher.final()]).toString('utf8');
}

/**
 * De challenge (4.3.4.2, stap 9): header, payload en de tijdelijke sleutel van het magazijn. Toetst
 * de vorm, niet de handtekening: de simulator ondertekent met een wegwerpsleutel die in geen
 * stelseldocument staat.
 * TODO(#1215): de handtekening toetsen aan `public_keys` van het magazijn zodra het stelseldocument
 * die noemt.
 */
function leesChallenge(compact) {
  const { header, payload } = leesJws(compact);

  if (header.alg !== 'ES256' || header.typ !== 'jwt' || typeof header.kid !== 'string' || header.kid === '') {
    throw new Error(`onverwachte challenge-header: ${JSON.stringify(header)}`);
  }

  if (payload.sub !== 'challenge' || typeof payload.nonce !== 'string' || typeof payload.ephemeral_organization_ec_public_key !== 'string') {
    throw new Error('onverwachte challenge-payload');
  }

  const tijdelijk = crypto.createPublicKey({ key: Buffer.from(payload.ephemeral_organization_ec_public_key, 'base64'), format: 'der', type: 'spki' });

  if (tijdelijk.asymmetricKeyType !== 'ec' || tijdelijk.asymmetricKeyDetails.namedCurve !== 'prime256v1') {
    throw new Error('de tijdelijke sleutel ligt niet op P-256');
  }

  return { header, payload, tijdelijk };
}

/**
 * De sleutel van de lokale App Manager: PKCS#8, base64url, zoals `maak-sleutel.mjs` hem in `.env`
 * zet.
 */
function appManagerSleutel(pkcs8) {
  if (typeof pkcs8 !== 'string' || pkcs8 === '') {
    throw new Error('RECHTSTREEKS_APP_MANAGER_SLEUTEL ontbreekt in bruno/berichtenmagazijn/.env; draai eerst `npm run sleutel` in bruno/berichtenmagazijn');
  }

  return crypto.createPrivateKey({ key: Buffer.from(pkcs8, 'base64url'), format: 'der', type: 'pkcs8' });
}

/**
 * Stap 13 tot en met 20 van `CreateSession` (de controle van de challenge, stap 10 tot en met 12,
 * ontbreekt; zie [leesChallenge]): een nieuw sleutelpaar voor de app, een credential van
 * de lokale App Manager op die sleutel, de ondertekende nonce, een nieuwe AES-sleutel, en de
 * presentatie versleuteld naar de tijdelijke sleutel uit de challenge. Geeft de body en de
 * AES-sleutel (base64url); de private key van de app is daarna niet meer nodig.
 */
function bouwSessieverzoek({ challenge, magazijnOin, ontvanger, appManager, nu }) {
  const app = sleutelpaar();

  const credential = es256({
    cnf: { jwk: publiekeJwk(app.publicKey) },
    scope: SCOPE,
    iss: appManager.oin,
    iat: nu,
    nbf: nu,
    exp: nu + CREDENTIAL_SECONDEN,
    ontvanger,
  }, appManager.sleutel, appManager.kid);

  const aesSleutel = crypto.randomBytes(AES_BYTES);

  const presentatie = JSON.stringify({
    app_nonce_signature: es256({ sub: 'challenge_response', aud: magazijnOin, nonce: challenge.payload.nonce }, app.privateKey),
    certificate_type: 'app_manager_jwt_certificate',
    certificate: credential,
    session_aes_key: aesSleutel.toString('base64'),
  });

  return {
    body: { nonce: challenge.payload.nonce, encrypted_challenge_result: jweEcdhEs(presentatie, challenge.tijdelijk) },
    aesSleutel: b64url(aesSleutel),
  };
}

/** Een versleuteld antwoord binnen de sessie, als object. */
function leesAntwoord(compact, aesSleutel) {
  return JSON.parse(ontsleutelDir(compact, Buffer.from(aesSleutel, 'base64url')));
}

function leesDeel(tekst) {
  try {
    return JSON.parse(Buffer.from(tekst, 'base64url').toString('utf8'));
  } catch {
    throw new Error('deel van JOSE-object is geen base64url-JSON');
  }
}

module.exports = {
  LOKALE_APP_MANAGER,
  SCOPE,
  appManagerSleutel,
  bouwSessieverzoek,
  concatKdf,
  es256,
  jweDir,
  jweEcdhEs,
  leesAntwoord,
  leesChallenge,
  leesJws,
  ontsleutelDir,
  publiekeJwk,
  sleutelpaar,
};
