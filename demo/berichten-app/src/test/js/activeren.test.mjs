import { test } from 'node:test';
import assert from 'node:assert/strict';
import { CompactEncrypt, CompactSign, compactDecrypt, decodeJwt, errors } from 'jose';
import {
  ActiverenFout, VerbindingFout, haalCredential, leesCredential, leesRegistratie, maakSessie, publiekeJwk, registratieAdres,
} from '../../main/resources/META-INF/resources/js/activeren.js';
import { maakSleutelpaar } from '../../main/resources/META-INF/resources/js/sleutelkluis.js';
import {
  BASIS, OIN, aesSleutel, credentialMet, nepAppManager, nepFetch, registratiebericht,
} from './hulp.mjs';

const encoder = new TextEncoder();

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

  await assert.rejects(leesRegistratie(fragment, await aesSleutel(), BASIS), errors.JOSEError);
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

  await assert.rejects(
    haalCredential({ basis: BASIS, sessieToken: 'sessie-1', aesSleutel: await aesSleutel(), registratieToken: 'geheim', fetch }),
    { name: 'ActiverenFout', status: 401 },
  );

  const { opties } = verzoeken[0];

  assert.equal(opties.headers.Authorization, 'sessie-1');
  assert.equal(opties.headers['Content-Type'], 'application/jose');
  assert.equal(opties.body.split('.').length, 5);
  assert.equal(opties.body.includes('geheim'), false);
  // De App Manager weigert een JWE zonder typ JWE met een 400 (API Design Rules, /encryption/jwe).
  assert.deepEqual(JSON.parse(Buffer.from(opties.body.split('.')[0], 'base64url')), { alg: 'dir', enc: 'A256GCM', typ: 'JWE' });
});

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

test('een sessie levert het sessietoken en een AES-sleutel die niet te exporteren is', async () => {
  const sleutelpaar = await maakSleutelpaar();
  const appManager = nepAppManager();

  const { sessieToken, aesSleutel: sleutel } = await maakSessie({ basis: BASIS, oin: OIN, sleutelpaar, fetch: appManager.fetch });
  const [presentatie] = appManager.log.presentaties;
  const nonceHandtekening = decodeJwt(presentatie.app_nonce_signature);

  assert.equal(sessieToken, 'sessie-1');
  assert.equal(sleutel.extractable, false);
  assert.equal(sleutel.algorithm.name, 'AES-GCM');
  assert.equal(presentatie.certificate_type, 'certificate_type_self_signed');
  assert.deepEqual(decodeJwt(presentatie.certificate), { sub: 'certificate_type_self_signed', cnf: { jwk: await publiekeJwk(sleutelpaar.publicKey) } });
  assert.deepEqual(nonceHandtekening, { sub: 'challenge_response', aud: OIN, nonce: 'nonce-0' });
  assert.equal(Buffer.from(presentatie.session_aes_key, 'base64').length, 32);
});

test('een 400 van de App Manager komt terug als fout met die status, bij complete en bij credential', async () => {
  const sleutelpaar = await maakSleutelpaar();

  await assert.rejects(
    maakSessie({ basis: BASIS, oin: OIN, sleutelpaar, fetch: nepAppManager({ status: { '/v1/complete': 400 } }).fetch }),
    { name: 'ActiverenFout', status: 400, stap: 'complete' },
  );

  const { fetch } = nepFetch({ '/credential': [400, ''] });

  await assert.rejects(
    haalCredential({ basis: BASIS, sessieToken: 's', aesSleutel: await aesSleutel(), registratieToken: 'r', fetch }),
    { name: 'ActiverenFout', status: 400, stap: 'credential' },
  );
});

test('een challenge met een onbruikbare tijdelijke sleutel is een fout van de App Manager', async () => {
  const sleutelpaar = await maakSleutelpaar();

  for (const sleutel of ['geen base64!', Buffer.from('geen sleutel').toString('base64')]) {
    const jws = await new CompactSign(encoder.encode(JSON.stringify({ sub: 'challenge', nonce: 'n', ephemeral_organization_ec_public_key: sleutel })))
      .setProtectedHeader({ alg: 'ES256' })
      .sign(sleutelpaar.privateKey);
    const { fetch } = nepFetch({ '/v1/challenge': [200, jws] });

    await assert.rejects(maakSessie({ basis: BASIS, oin: OIN, sleutelpaar, fetch }), { name: 'ActiverenFout', stap: 'challenge' });
  }
});

test('een registratiebericht met een ander sleutelalgoritme dan dir wordt geweigerd, ook onder de juiste sleutel', async () => {
  const sleutel = await aesSleutel();
  const jwe = await new CompactEncrypt(encoder.encode(JSON.stringify({ sub: 'registration_token', iss: BASIS, registration_token: 'r1' })))
    .setProtectedHeader({ alg: 'A256GCMKW', enc: 'A256GCM', typ: 'JWE' })
    .encrypt(sleutel);

  await assert.rejects(leesRegistratie(`#registration_token=${jwe}`, sleutel, BASIS), { code: 'ERR_JOSE_ALG_NOT_ALLOWED' });
});

test('een registratiebericht van een andere iss noemt beide adressen in de melding voor de console', async () => {
  const sleutel = await aesSleutel();
  const fragment = await registratiebericht({ sub: 'registration_token', iss: 'https://ander.test/', registration_token: 'r1' }, sleutel);

  await assert.rejects(leesRegistratie(fragment, sleutel, BASIS), (fout) => fout instanceof ActiverenFout
    && fout.message.includes('https://ander.test/') && fout.message.includes(BASIS));
});

test('een App Manager die bij complete of credential wegvalt, of halverwege afbreekt, geeft een VerbindingFout', async () => {
  const sleutelpaar = await maakSleutelpaar();
  const echt = nepAppManager();
  const valtWegBij = (pad) => async (adres, opties) => {
    if (new URL(adres).pathname === pad) throw new TypeError('fetch failed');

    return echt.fetch(adres, opties);
  };
  const afgebroken = async () => new Response(new ReadableStream({ start(stroom) { stroom.error(new Error('weg')); } }));

  await assert.rejects(maakSessie({ basis: BASIS, oin: OIN, sleutelpaar, fetch: valtWegBij('/v1/complete') }), VerbindingFout);
  await assert.rejects(
    haalCredential({ basis: BASIS, sessieToken: 's', aesSleutel: await aesSleutel(), registratieToken: 'r', fetch: valtWegBij('/credential') }),
    VerbindingFout,
  );
  await assert.rejects(maakSessie({ basis: BASIS, oin: OIN, sleutelpaar, fetch: afgebroken }), VerbindingFout);
});

test('een registratiebericht dat geen JSON is, geeft een fout zonder de ontsleutelde inhoud', async () => {
  const sleutel = await aesSleutel();
  const fout = await leesRegistratie(await registratiebericht('BSN 999999990', sleutel), sleutel, BASIS).catch((f) => f);

  assert.ok(fout instanceof ActiverenFout);
  assert.equal(fout.cause, undefined);
  assert.doesNotMatch(fout.message, /999999990/);
});
