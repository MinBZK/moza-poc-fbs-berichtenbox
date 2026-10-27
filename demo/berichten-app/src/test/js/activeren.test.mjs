import { test } from 'node:test';
import assert from 'node:assert/strict';
import { CompactEncrypt, CompactSign, compactDecrypt } from '../../main/resources/META-INF/resources/vendor/jose-6.2.13/index.js';
import {
  ActiverenFout, VerbindingFout, haalCredential, leesCredential, leesRegistratie, maakSessie, registratieAdres,
} from '../../main/resources/META-INF/resources/js/activeren.js';
import { maakSleutelpaar } from '../../main/resources/META-INF/resources/js/sleutelkluis.js';

const BASIS = 'https://app-manager.test/';
const encoder = new TextEncoder();

function aesSleutel() {
  return crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
}

async function registratiebericht(velden, sleutel) {
  const jwe = await new CompactEncrypt(encoder.encode(JSON.stringify(velden)))
    .setProtectedHeader({ alg: 'dir', enc: 'A256GCM' })
    .encrypt(sleutel);

  return `#registration_token=${jwe}`;
}

/** Een fetch die per pad één antwoord geeft en onthoudt wat hij kreeg. */
function nepFetch(antwoorden) {
  const verzoeken = [];
  const fetch = async (adres, opties) => {
    verzoeken.push({ adres: String(adres), opties });

    const [status, tekst] = antwoorden[new URL(adres).pathname] ?? [404, ''];

    return new Response(tekst, { status });
  };

  return { fetch, verzoeken };
}

test('het registratieadres zet het session_token als query-parameter, gecodeerd', () => {
  assert.equal(registratieAdres(BASIS, 'a+b/c'), 'https://app-manager.test/register_app?session_token=a%2Bb%2Fc');
});

test('een registratiebericht van de eigen App Manager levert het token op', async () => {
  const sleutel = await aesSleutel();
  const fragment = await registratiebericht({ sub: 'registration_token', iss: BASIS, registration_token: 'r1' }, sleutel);

  assert.equal(await leesRegistratie(fragment, sleutel, BASIS), 'r1');
});

test('een registratiebericht met een andere iss, een ander sub of zonder token wordt geweigerd', async () => {
  const sleutel = await aesSleutel();
  const varianten = [
    { sub: 'registration_token', iss: 'https://aanvaller.example/', registration_token: 'r1' },
    { sub: 'iets anders', iss: BASIS, registration_token: 'r1' },
    { sub: 'registration_token', iss: BASIS },
    { sub: 'registration_token', iss: BASIS, registration_token: 42 },
  ];

  for (const velden of varianten) {
    await assert.rejects(leesRegistratie(await registratiebericht(velden, sleutel), sleutel, BASIS), ActiverenFout);
  }
});

test('een registratiebericht onder een andere sleutel wordt geweigerd', async () => {
  const fragment = await registratiebericht({ sub: 'registration_token', iss: BASIS, registration_token: 'r1' }, await aesSleutel());

  await assert.rejects(leesRegistratie(fragment, await aesSleutel(), BASIS));
});

test('zonder registration_token in het fragment is er niets te lezen', async () => {
  for (const fragment of ['', '#', '#iets=anders', '#registration_token=', '#xregistration_token=r1']) {
    await assert.rejects(leesRegistratie(fragment, await aesSleutel(), BASIS), ActiverenFout);
  }
});

test('een challenge die geen challenge is, wordt geweigerd voordat er iets naar complete gaat', async () => {
  const sleutelpaar = await maakSleutelpaar();
  const onzinClaims = [
    { sub: 'iets anders', nonce: 'n', ephemeral_organization_ec_public_key: 'k' },
    { sub: 'challenge', nonce: 7, ephemeral_organization_ec_public_key: 'k' },
    { sub: 'challenge', nonce: 'n' },
  ];

  for (const claims of onzinClaims) {
    const jws = await new CompactSign(encoder.encode(JSON.stringify(claims)))
      .setProtectedHeader({ alg: 'ES256' })
      .sign(sleutelpaar.privateKey);
    const { fetch, verzoeken } = nepFetch({ '/v1/challenge': [200, jws] });

    await assert.rejects(maakSessie({ basis: BASIS, oin: '00000000000000001100', sleutelpaar, fetch }), ActiverenFout);
    assert.deepEqual(verzoeken.map((v) => new URL(v.adres).pathname), ['/v1/challenge']);
  }
});

test('een App Manager die niet te bereiken is, geeft een VerbindingFout met de oorzaak', async () => {
  const fetch = async () => {
    throw new TypeError('fetch failed');
  };

  await assert.rejects(
    maakSessie({ basis: BASIS, oin: '00000000000000001100', sleutelpaar: await maakSleutelpaar(), fetch }),
    (fout) => fout instanceof VerbindingFout && fout.cause instanceof TypeError,
  );
});

test('een fout van de App Manager komt terug met zijn status en de stap', async () => {
  const { fetch } = nepFetch({ '/v1/challenge': [503, ''], '/credential': [401, ''] });

  await assert.rejects(
    maakSessie({ basis: BASIS, oin: '00000000000000001100', sleutelpaar: await maakSleutelpaar(), fetch }),
    { name: 'ActiverenFout', status: 503, stap: 'challenge' },
  );
  await assert.rejects(
    haalCredential({ basis: BASIS, sessieToken: 's', aesSleutel: await aesSleutel(), registratieToken: 'r', fetch }),
    { name: 'ActiverenFout', status: 401, stap: 'credential' },
  );
});

test('het verzoek om een credential draagt het sessietoken in Authorization en is versleuteld', async () => {
  const { fetch, verzoeken } = nepFetch({ '/credential': [401, ''] });

  await assert.rejects(haalCredential({ basis: BASIS, sessieToken: 'sessie-1', aesSleutel: await aesSleutel(), registratieToken: 'geheim', fetch }));

  const { opties } = verzoeken[0];

  assert.equal(opties.headers.Authorization, 'sessie-1');
  assert.equal(opties.headers['Content-Type'], 'application/jose');
  assert.equal(opties.body.split('.').length, 5);
  assert.equal(opties.body.includes('geheim'), false);
});

async function credentialMet(claims) {
  const { privateKey } = await maakSleutelpaar();

  return new CompactSign(encoder.encode(JSON.stringify(claims))).setProtectedHeader({ alg: 'ES256' }).sign(privateKey);
}

test('van een credential toont de app de ontvanger en het verloopmoment', async () => {
  const credential = await credentialMet({ ontvanger: 'KVK:90000014', exp: 1_800_000_000 });

  assert.deepEqual(leesCredential(credential), { ontvanger: 'KVK:90000014', verlooptOp: new Date(1_800_000_000_000) });
});

test('een credential zonder ontvanger of verloopmoment is onbruikbaar', async () => {
  const varianten = [{ exp: 1_800_000_000 }, { ontvanger: 'KVK:90000014' }, { ontvanger: 'KVK:90000014', exp: 'morgen' }, { ontvanger: 7, exp: 1 }];

  for (const claims of varianten) {
    await assert.rejects(async () => leesCredential(await credentialMet(claims)), ActiverenFout);
  }
});

test('een antwoord op complete zonder sessietoken wordt geweigerd', async () => {
  const app = await maakSleutelpaar();
  const verifier = await crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, ['deriveBits']);
  const spki = Buffer.from(await crypto.subtle.exportKey('spki', verifier.publicKey)).toString('base64');
  const challenge = await new CompactSign(encoder.encode(JSON.stringify({ sub: 'challenge', nonce: 'n', ephemeral_organization_ec_public_key: spki })))
    .setProtectedHeader({ alg: 'ES256' })
    .sign(app.privateKey);

  // De Verifier zou het antwoord onder de AES-sleutel uit de presentatie versleutelen; die haalt
  // deze nep-Verifier uit het complete-verzoek.
  const fetch = async (adres, opties) => {
    if (new URL(adres).pathname === '/v1/challenge') return new Response(challenge);

    const { encrypted_challenge_result: jwe } = JSON.parse(opties.body);
    const { plaintext } = await compactDecrypt(jwe, verifier.privateKey);
    const ruw = Buffer.from(JSON.parse(new TextDecoder().decode(plaintext)).session_aes_key, 'base64');
    const sleutel = await crypto.subtle.importKey('raw', ruw, 'AES-GCM', false, ['encrypt']);
    const antwoord = await new CompactEncrypt(encoder.encode(JSON.stringify({ expires_in: 600 })))
      .setProtectedHeader({ alg: 'dir', enc: 'A256GCM' })
      .encrypt(sleutel);

    return new Response(antwoord);
  };

  await assert.rejects(maakSessie({ basis: BASIS, oin: '00000000000000001100', sleutelpaar: app, fetch }), ActiverenFout);
});
