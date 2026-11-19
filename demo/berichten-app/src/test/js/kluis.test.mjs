import { beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { IDBFactory } from 'fake-indexeddb';
import * as kluis from '../../main/resources/META-INF/resources/js/sleutelkluis.js';

// Elke test een lege database; fake-indexeddb volgt de IndexedDB-specificatie, ook voor CryptoKey.
beforeEach(() => {
  globalThis.indexedDB = new IDBFactory();
});

const publiek = async (paar) => crypto.subtle.exportKey('jwk', paar.publicKey);

test('het sleutelpaar wordt één keer gemaakt, bewaard en blijft niet te exporteren', async () => {
  const eerste = await kluis.sleutelpaar();
  const tweede = await kluis.sleutelpaar();

  assert.deepEqual(await publiek(tweede), await publiek(eerste));
  assert.equal(tweede.privateKey.extractable, false);
  await assert.rejects(crypto.subtle.exportKey('pkcs8', tweede.privateKey));
});

test('twee aanroepen tegelijk krijgen hetzelfde paar, en dat is het bewaarde', async () => {
  const [a, b] = await Promise.all([kluis.sleutelpaar(), kluis.sleutelpaar()]);

  assert.deepEqual(await publiek(a), await publiek(b));
  assert.deepEqual(await publiek(await kluis.haal('sleutelpaar')), await publiek(a));
});

test('twee tabbladen tegelijk: het paar dat als eerste bewaard is, geldt voor allebei', async () => {
  // Een tweede exemplaar van de module, zoals een ander tabblad dat heeft: eigen `lopend`, dezelfde database.
  const anderTabblad = await import('../../main/resources/META-INF/resources/js/sleutelkluis.js?tabblad=2');

  const [a, b] = await Promise.all([kluis.sleutelpaar(), anderTabblad.sleutelpaar()]);
  const bewaard = await publiek(await kluis.haal('sleutelpaar'));

  assert.deepEqual(await publiek(a), bewaard);
  assert.deepEqual(await publiek(b), bewaard);
});

test('een sleutelpaar dat er al staat, wordt nooit vervangen', async () => {
  const bestaand = await kluis.maakSleutelpaar();

  await kluis.bewaar('sleutelpaar', bestaand);

  assert.deepEqual(await publiek(await kluis.sleutelpaar()), await publiek(bestaand));
});

test('een nieuwe registratie wist in één keer het oude credential en het afgeronde activeren', async () => {
  await kluis.bewaar(kluis.CREDENTIAL, 'oud credential');
  await kluis.bewaar(kluis.ACTIVATIE, { sessieToken: 's' });

  await kluis.vervangRegistratie('r2');

  assert.equal(await kluis.haal(kluis.REGISTRATIE), 'r2');
  assert.equal(await kluis.haal(kluis.CREDENTIAL), undefined);
  assert.equal(await kluis.haal(kluis.ACTIVATIE), undefined);
});

test('een credential wordt alleen bewaard bij de registratie waarvoor het is opgehaald', async () => {
  await kluis.vervangRegistratie('r1');

  assert.equal(await kluis.bewaarCredential('r1', 'c1'), true);
  assert.equal(await kluis.haal(kluis.CREDENTIAL), 'c1');

  await kluis.vervangRegistratie('r2');

  assert.equal(await kluis.bewaarCredential('r1', 'c-van-r1'), false);
  assert.equal(await kluis.haal(kluis.CREDENTIAL), undefined);
});

test('een registratie wordt alleen vergeten als hij nog dezelfde is', async () => {
  await kluis.vervangRegistratie('r1');
  await kluis.bewaarCredential('r1', 'c1');

  assert.equal(await kluis.vergeetRegistratie('r0'), false);
  assert.equal(await kluis.haal(kluis.REGISTRATIE), 'r1');
  assert.equal(await kluis.haal(kluis.CREDENTIAL), 'c1');

  assert.equal(await kluis.vergeetRegistratie('r1'), true);
  assert.equal(await kluis.haal(kluis.REGISTRATIE), undefined);
  assert.equal(await kluis.haal(kluis.CREDENTIAL), undefined);
});

test('wissen haalt alles weg, ook het sleutelpaar', async () => {
  await kluis.sleutelpaar();
  await kluis.vervangRegistratie('r1');

  await kluis.wis();

  assert.equal(await kluis.haal('sleutelpaar'), undefined);
  assert.equal(await kluis.haal(kluis.REGISTRATIE), undefined);
});

test('een waarde die de opslag niet aanneemt, geeft een KluisFout', async () => {
  await assert.rejects(kluis.bewaar('iets', () => 'geen kloonbare waarde'), kluis.KluisFout);
});

test('een browser die de opslag verbiedt, geeft een KluisFout en geen kale fout', async () => {
  globalThis.indexedDB = {
    open() {
      throw new DOMException('opslag verboden', 'SecurityError');
    },
  };

  await assert.rejects(kluis.haal(kluis.REGISTRATIE), (fout) => fout instanceof kluis.KluisFout && fout.cause?.name === 'SecurityError');
});
