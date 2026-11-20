// De JOSE-code van de Bruno-scripts tegen jose als tweede implementatie: wat lib/rechtstreeks.js
// maakt, moet jose kunnen lezen, en andersom. Zonder magazijn; de keten zelf toetst `bru run`.
//
//   npm test
import { createRequire } from 'node:module';
import { createCipheriv, generateKeyPairSync, randomBytes } from 'node:crypto';
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import * as jose from 'jose';

const require = createRequire(import.meta.url);
const r = require('./rechtstreeks.js');

const MAGAZIJN = '00000009000000000001';
const NU = 1_800_000_000;

function challenge({ sub = 'challenge', nonce = 'bm9uY2Utdm9vci1kZS10ZXN0LTEyMw==', curve = 'P-256', kid = 'magazijn-1' } = {}) {
  const tijdelijk = generateKeyPairSync('ec', { namedCurve: curve });
  const magazijn = generateKeyPairSync('ec', { namedCurve: 'P-256' });
  const spki = tijdelijk.publicKey.export({ format: 'der', type: 'spki' }).toString('base64');
  const jws = r.es256({ sub, nonce, ephemeral_organization_ec_public_key: spki }, magazijn.privateKey, kid);

  return { jws, tijdelijk };
}

describe('es256', () => {
  it('geeft een JWS die jose met de publieke sleutel controleert', async () => {
    const { publicKey, privateKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
    const jws = r.es256({ sub: 'x' }, privateKey, 'k1');
    const { payload, protectedHeader } = await jose.compactVerify(jws, publicKey);

    assert.deepEqual(protectedHeader, { alg: 'ES256', typ: 'jwt', kid: 'k1' });
    assert.deepEqual(JSON.parse(new TextDecoder().decode(payload)), { sub: 'x' });
  });

  it('laat kid weg als er geen is', async () => {
    const { publicKey, privateKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });

    assert.deepEqual((await jose.compactVerify(r.es256({}, privateKey), publicKey)).protectedHeader, { alg: 'ES256', typ: 'jwt' });
  });
});

describe('jweEcdhEs', () => {
  it('geeft een JWE die jose met de private key van het magazijn opent', async () => {
    const magazijn = generateKeyPairSync('ec', { namedCurve: 'P-256' });
    const jwe = r.jweEcdhEs('{"a":1}', magazijn.publicKey);
    const { plaintext, protectedHeader } = await jose.compactDecrypt(jwe, magazijn.privateKey, {
      keyManagementAlgorithms: ['ECDH-ES'], contentEncryptionAlgorithms: ['A256GCM'],
    });

    assert.equal(new TextDecoder().decode(plaintext), '{"a":1}');
    assert.deepEqual(Object.keys(protectedHeader).sort(), ['alg', 'enc', 'epk', 'typ']);
    assert.equal(protectedHeader.typ, 'JWE');
    assert.deepEqual(Object.keys(protectedHeader.epk).sort(), ['crv', 'kty', 'x', 'y']);
  });
});

describe('ontsleutelDir', () => {
  const sleutel = randomBytes(32);
  const maak = (header, inhoud = '{"b":2}', key = sleutel) => new jose.CompactEncrypt(new TextEncoder().encode(inhoud)).setProtectedHeader(header).encrypt(key);

  it('leest een antwoord dat jose versleutelde', async () => {
    assert.equal(r.ontsleutelDir(await maak({ alg: 'dir', enc: 'A256GCM', typ: 'JWE' }), sleutel), '{"b":2}');
    assert.deepEqual(r.leesAntwoord(await maak({ alg: 'dir', enc: 'A256GCM', typ: 'JWE' }), sleutel.toString('base64url')), { b: 2 });
  });

  it('jose leest wat jweDir versleutelde', async () => {
    const { plaintext } = await jose.compactDecrypt(r.jweDir('{"c":3}', sleutel), sleutel);

    assert.equal(new TextDecoder().decode(plaintext), '{"c":3}');
  });

  for (const [naam, header] of [
    ['zonder typ', { alg: 'dir', enc: 'A256GCM' }],
    ['met een ander enc', { alg: 'dir', enc: 'A128GCM' }],
    ['met een extra veld', { alg: 'dir', enc: 'A256GCM', typ: 'JWE', kid: 'x' }],
    ['met typ JWT', { alg: 'dir', enc: 'A256GCM', typ: 'JWT' }],
  ]) {
    it(`weigert een header ${naam}`, async () => {
      const jwe = await maak(header, '{}', header.enc === 'A128GCM' ? sleutel.subarray(0, 16) : sleutel);

      assert.throws(() => r.ontsleutelDir(jwe, sleutel), /onverwachte JWE-header/);
    });
  }

  it('weigert een ander alg met dezelfde velden', () => {
    const delen = r.jweDir('{}', sleutel).split('.');

    delen[0] = Buffer.from(JSON.stringify({ alg: 'A256KW', enc: 'A256GCM', typ: 'JWE' })).toString('base64url');

    assert.throws(() => r.ontsleutelDir(delen.join('.'), sleutel), /onverwachte JWE-header/);
  });

  it('weigert een antwoord met een andere sleutel', async () => {
    assert.throws(() => r.ontsleutelDir(r.jweDir('{}', randomBytes(32)), sleutel));
  });

  it('weigert een gewijzigde tag', () => {
    const delen = r.jweDir('{}', sleutel).split('.');

    delen[4] = Buffer.alloc(16).toString('base64url');

    assert.throws(() => r.ontsleutelDir(delen.join('.'), sleutel));
  });

  it('weigert een IV van een andere lengte dan 96 bit, ook als de tag klopt', () => {
    const header = Buffer.from(JSON.stringify({ alg: 'dir', enc: 'A256GCM', typ: 'JWE' })).toString('base64url');
    const iv = randomBytes(16);
    const cipher = createCipheriv('aes-256-gcm', sleutel, iv);

    cipher.setAAD(Buffer.from(header, 'ascii'));

    const versleuteld = Buffer.concat([cipher.update('{}'), cipher.final()]);
    const jwe = [header, '', iv, versleuteld, cipher.getAuthTag()].map((d) => (typeof d === 'string' ? d : d.toString('base64url'))).join('.');

    assert.throws(() => r.ontsleutelDir(jwe, sleutel), /IV of tag/);
  });

  for (const [naam, aanpassen] of [['vier', (d) => d.slice(0, 4)], ['zes', (d) => [...d, 'extra']]]) {
    it(`weigert een JWE met ${naam} delen`, () => {
      assert.throws(() => r.ontsleutelDir(aanpassen(r.jweDir('{}', sleutel).split('.')).join('.'), sleutel), /vijf delen/);
    });
  }

  it('weigert een JWE met een versleutelde sleutel', () => {
    const delen = r.jweDir('{}', sleutel).split('.');

    delen[1] = 'AAAA';

    assert.throws(() => r.ontsleutelDir(delen.join('.'), sleutel), /lege sleutel/);
  });
});

describe('leesChallenge', () => {
  it('geeft nonce en tijdelijke sleutel', () => {
    const { jws } = challenge();
    const gelezen = r.leesChallenge(jws);

    assert.equal(gelezen.payload.nonce, 'bm9uY2Utdm9vci1kZS10ZXN0LTEyMw==');
    assert.equal(gelezen.header.kid, 'magazijn-1');
    assert.equal(gelezen.tijdelijk.asymmetricKeyDetails.namedCurve, 'prime256v1');
  });

  it('weigert een andere sub', () => assert.throws(() => r.leesChallenge(challenge({ sub: 'iets' }).jws), /payload/));

  it('weigert een challenge zonder nonce', () => assert.throws(() => r.leesChallenge(challenge({ nonce: null }).jws), /payload/));

  it('weigert een challenge zonder kid', () => assert.throws(() => r.leesChallenge(challenge({ kid: null }).jws), /header/));

  it('weigert een tijdelijke sleutel op P-384', () => assert.throws(() => r.leesChallenge(challenge({ curve: 'P-384' }).jws), /P-256/));

  for (const [naam, header] of [
    ['met ES384', { alg: 'ES384', typ: 'jwt', kid: 'k' }],
    ['zonder typ', { alg: 'ES256', kid: 'k' }],
    ['met een lege kid', { alg: 'ES256', typ: 'jwt', kid: '' }],
    ['met een kid die geen tekst is', { alg: 'ES256', typ: 'jwt', kid: 7 }],
  ]) {
    it(`weigert een header ${naam}`, () => {
      const [, payload, handtekening] = challenge().jws.split('.');
      const jws = [Buffer.from(JSON.stringify(header)).toString('base64url'), payload, handtekening].join('.');

      assert.throws(() => r.leesChallenge(jws), /header/);
    });
  }

  it('weigert een payload zonder tijdelijke sleutel', () => {
    const jws = r.es256({ sub: 'challenge', nonce: 'bm9uY2Utdm9vci1kZS10ZXN0LTEyMw==' }, generateKeyPairSync('ec', { namedCurve: 'P-256' }).privateKey, 'k');

    assert.throws(() => r.leesChallenge(jws), /payload/);
  });

  for (const compact of ['a.b', 'a.b.c.d']) {
    it(`weigert ${compact} als JWS`, () => assert.throws(() => r.leesChallenge(compact), /drie delen/));
  }
});

describe('bouwSessieverzoek', () => {
  it('maakt een presentatie die het magazijn kan openen en controleren', async () => {
    const { jws, tijdelijk } = challenge();
    const appManager = generateKeyPairSync('ec', { namedCurve: 'P-256' });
    const pkcs8 = appManager.privateKey.export({ format: 'der', type: 'pkcs8' }).toString('base64url');

    const { body, aesSleutel } = r.bouwSessieverzoek({
      challenge: r.leesChallenge(jws),
      magazijnOin: MAGAZIJN,
      ontvanger: 'KVK:90000014',
      appManager: { oin: '00000000000000001199', kid: 'bruno-lokaal', sleutel: r.appManagerSleutel(pkcs8) },
      nu: NU,
    });

    assert.equal(body.nonce, 'bm9uY2Utdm9vci1kZS10ZXN0LTEyMw==');

    const { plaintext } = await jose.compactDecrypt(body.encrypted_challenge_result, tijdelijk.privateKey);
    const presentatie = JSON.parse(new TextDecoder().decode(plaintext));

    assert.deepEqual(Object.keys(presentatie).sort(), ['app_nonce_signature', 'certificate', 'certificate_type', 'session_aes_key']);
    assert.equal(presentatie.certificate_type, 'app_manager_jwt_certificate');
    // Base64 met padding, geen base64url: het magazijn decodeert `format: byte`.
    assert.match(presentatie.session_aes_key, /^[A-Za-z0-9+/]{43}=$/);
    assert.equal(Buffer.from(presentatie.session_aes_key, 'base64').toString('base64url'), aesSleutel);
    assert.equal(Buffer.from(aesSleutel, 'base64url').length, 32);

    const credential = await jose.compactVerify(presentatie.certificate, appManager.publicKey);
    const claims = JSON.parse(new TextDecoder().decode(credential.payload));

    assert.deepEqual(credential.protectedHeader, { alg: 'ES256', typ: 'jwt', kid: 'bruno-lokaal' });
    assert.deepEqual({ ...claims, cnf: undefined }, {
      cnf: undefined, scope: 'nl.rijksoverheid.moz.fbs.berichten', iss: '00000000000000001199', iat: NU, nbf: NU, exp: NU + 3600, ontvanger: 'KVK:90000014',
    });
    assert.deepEqual(Object.keys(claims.cnf.jwk).sort(), ['crv', 'kty', 'x', 'y']);

    const handtekening = await jose.compactVerify(presentatie.app_nonce_signature, await jose.importJWK({ ...claims.cnf.jwk, alg: 'ES256' }));

    assert.deepEqual(JSON.parse(new TextDecoder().decode(handtekening.payload)), {
      sub: 'challenge_response', aud: MAGAZIJN, nonce: 'bm9uY2Utdm9vci1kZS10ZXN0LTEyMw==',
    });
  });

  it('geeft elke keer een andere AES-sleutel en app-sleutel', async () => {
    const appManager = { oin: '00000000000000001199', kid: 'k', sleutel: generateKeyPairSync('ec', { namedCurve: 'P-256' }).privateKey };

    const maak = async () => {
      const { jws, tijdelijk } = challenge();
      const { body, aesSleutel } = r.bouwSessieverzoek({ challenge: r.leesChallenge(jws), magazijnOin: MAGAZIJN, ontvanger: 'KVK:90000014', appManager, nu: NU });
      const { plaintext } = await jose.compactDecrypt(body.encrypted_challenge_result, tijdelijk.privateKey);

      return { aesSleutel, jwk: jose.decodeJwt(JSON.parse(new TextDecoder().decode(plaintext)).certificate).cnf.jwk };
    };

    const [een, twee] = [await maak(), await maak()];

    assert.notEqual(een.aesSleutel, twee.aesSleutel);
    assert.notEqual(een.jwk.x, twee.jwk.x);
  });
});

describe('appManagerSleutel', () => {
  for (const waarde of [undefined, null, '']) {
    it(`noemt npm run sleutel bij ${JSON.stringify(waarde)}`, () => assert.throws(() => r.appManagerSleutel(waarde), /npm run sleutel/));
  }

  it('leest PKCS#8 als base64url', () => {
    const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
    const gelezen = r.appManagerSleutel(privateKey.export({ format: 'der', type: 'pkcs8' }).toString('base64url'));

    assert.equal(gelezen.asymmetricKeyDetails.namedCurve, 'prime256v1');
    assert.equal(gelezen.type, 'private');
  });
});
