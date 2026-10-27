import { test } from 'node:test';
import assert from 'node:assert/strict';
import { maakSleutelpaar } from '../../main/resources/META-INF/resources/js/sleutelkluis.js';
import { publiekeJwk } from '../../main/resources/META-INF/resources/js/activeren.js';

test('de private key van de app is niet te exporteren, in geen enkel formaat', async () => {
  const { privateKey } = await maakSleutelpaar();

  assert.equal(privateKey.extractable, false);

  // De spec noemt de fout InvalidAccessError; Node 22 noemt hem InvalidAccessException.
  for (const formaat of ['pkcs8', 'jwk']) {
    await assert.rejects(crypto.subtle.exportKey(formaat, privateKey), { name: /^InvalidAccess(Error|Exception)$/ });
  }
});

test('met de sleutel tekent de app, en de handtekening klopt met de public key', async () => {
  const { privateKey, publicKey } = await maakSleutelpaar();
  const bericht = new TextEncoder().encode('challenge_response');
  const algoritme = { name: 'ECDSA', hash: 'SHA-256' };
  const handtekening = await crypto.subtle.sign(algoritme, privateKey, bericht);

  assert.equal(await crypto.subtle.verify(algoritme, publicKey, handtekening, bericht), true);
});

test('de public key gaat als JWK zonder ext en key_ops naar buiten', async () => {
  const jwk = await publiekeJwk((await maakSleutelpaar()).publicKey);

  assert.deepEqual(Object.keys(jwk).sort(), ['crv', 'kty', 'x', 'y']);
  assert.equal(jwk.crv, 'P-256');
});

test('elk sleutelpaar is nieuw', async () => {
  const een = await publiekeJwk((await maakSleutelpaar()).publicKey);
  const twee = await publiekeJwk((await maakSleutelpaar()).publicKey);

  assert.notEqual(een.x, twee.x);
});
