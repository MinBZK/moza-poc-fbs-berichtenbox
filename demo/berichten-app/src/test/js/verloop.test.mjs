import { beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { IDBFactory } from 'fake-indexeddb';
import { errors } from 'jose';
import { ActiverenFout, VerbindingFout } from '../../main/resources/META-INF/resources/js/activeren.js';
import * as kluis from '../../main/resources/META-INF/resources/js/sleutelkluis.js';
import {
  ACTIVATIE_SESSIE, Melding, OPNIEUW_ACTIVEREN, OPSLAG_GEWEIGERD, activeer, melding, neemFragment, rondActiverenAf, statustekst, vernieuw, wis,
} from '../../main/resources/META-INF/resources/js/verloop.js';
import { BASIS, OIN, credentialMet, nepAppManager, registratiebericht } from './hulp.mjs';

const APP_MANAGER = { url: BASIS, oin: OIN };

/** sessionStorage van één tabblad. */
function nepSessie() {
  const waarden = new Map();

  return {
    getItem: (naam) => waarden.get(naam) ?? null,
    setItem: (naam, waarde) => waarden.set(naam, String(waarde)),
    removeItem: (naam) => waarden.delete(naam),
  };
}

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory();
});

/** Een tabblad dat activeert en terugkomt met het fragment dat de App Manager meegeeft. */
async function terugVanAppManager({ credential, status } = {}) {
  const appManager = nepAppManager({ credential, status });
  const sessie = nepSessie();
  const omgeving = { appManager: APP_MANAGER, kluis, sessie, fetch: appManager.fetch };

  await activeer(omgeving);

  const fragment = await registratiebericht({ sub: 'registration_token', iss: BASIS, registration_token: 'r1' }, appManager.sessieSleutel());

  return { omgeving: { ...omgeving, fragment }, appManager, sessie };
}

test('het fragment met het token gaat meteen uit de adresbalk en de geschiedenis', () => {
  const geschiedenis = [];
  const vervang = { replaceState: (...argumenten) => geschiedenis.push(argumenten) };

  assert.equal(neemFragment({ hash: '#registration_token=abc', pathname: '/', search: '?x=1' }, vervang), '#registration_token=abc');
  assert.deepEqual(geschiedenis, [[null, '', '/?x=1']]);

  assert.equal(neemFragment({ hash: '#iets=anders', pathname: '/', search: '' }, vervang), null);
  assert.equal(neemFragment({ hash: '', pathname: '/', search: '' }, vervang), null);
  assert.equal(geschiedenis.length, 1);
});

test('activeren onthoudt het sessietoken in het tabblad en de sleutel in de kluis, en geeft het adres van de App Manager', async () => {
  const sessie = nepSessie();

  const adres = await activeer({ appManager: APP_MANAGER, kluis, sessie, fetch: nepAppManager().fetch });

  assert.equal(adres, `${BASIS}register_app?session_token=sessie-1`);
  assert.equal(sessie.getItem(ACTIVATIE_SESSIE), 'sessie-1');
  assert.equal((await kluis.haal(kluis.ACTIVATIE)).sessieToken, 'sessie-1');
  assert.equal((await kluis.haal(kluis.ACTIVATIE)).aesSleutel.extractable, false);
});

test('terug van de App Manager: de registratie en het bewijs staan in de kluis, het activeren is opgeruimd', async () => {
  const credential = await credentialMet({ ontvanger: 'KVK:90000014', exp: 1_800_000_000 });
  const { omgeving, appManager, sessie } = await terugVanAppManager({ credential });

  await rondActiverenAf(omgeving);

  assert.equal(await kluis.haal(kluis.REGISTRATIE), 'r1');
  assert.equal(await kluis.haal(kluis.CREDENTIAL), credential);
  assert.equal(await kluis.haal(kluis.ACTIVATIE), undefined);
  assert.equal(sessie.getItem(ACTIVATIE_SESSIE), null);
  assert.deepEqual(appManager.log.credentialVerzoeken, [{ sub: 'registration_token', iss: 'bk-connect://app/', registration_token: 'r1' }]);
});

test('een activering die een ander tabblad begon, blijft voor dat tabblad staan', async () => {
  const { omgeving } = await terugVanAppManager();

  const zonderActivering = nepSessie();
  const metEenAndere = nepSessie();

  metEenAndere.setItem(ACTIVATIE_SESSIE, 'sessie-van-een-ander-tabblad');

  for (const sessie of [zonderActivering, metEenAndere]) {
    await assert.rejects(rondActiverenAf({ ...omgeving, sessie }), (fout) => fout instanceof Melding && fout.message.includes('ander tabblad'));
  }

  assert.equal((await kluis.haal(kluis.ACTIVATIE)).sessieToken, 'sessie-1');
  assert.equal(await kluis.haal(kluis.REGISTRATIE), undefined);
});

test('lukt het bewaren van de registratie niet, dan zegt de melding dat opnieuw activeren nodig is', async () => {
  const { omgeving } = await terugVanAppManager();
  const weigert = { ...kluis, vervangRegistratie: async () => { throw new kluis.KluisFout('vol'); } };

  await assert.rejects(rondActiverenAf({ ...omgeving, kluis: weigert }), (fout) => fout instanceof Melding
    && fout.message.includes(OPNIEUW_ACTIVEREN) && fout.cause instanceof kluis.KluisFout);
  assert.equal((await kluis.haal(kluis.ACTIVATIE)).sessieToken, 'sessie-1');
});

for (const [omstandigheid, status] of [['een 401', 401], ['een 503', 503]]) {
  test(`na ${omstandigheid} op het ophalen van het bewijs blijft de registratie staan en wijst de melding naar vernieuwen`, async () => {
    const { omgeving } = await terugVanAppManager({ status: { '/credential': status } });

    await assert.rejects(rondActiverenAf(omgeving), (fout) => fout instanceof Melding && fout.message.includes('Bewijs vernieuwen'));
    assert.equal(await kluis.haal(kluis.REGISTRATIE), 'r1');
  });
}

test('valt de verbinding weg bij het ophalen van het bewijs, dan wijst de melding ook naar vernieuwen', async () => {
  const { omgeving } = await terugVanAppManager();
  const wegBijCredential = async (adres, opties) => {
    if (new URL(adres).pathname === '/credential') throw new TypeError('fetch failed');

    return omgeving.fetch(adres, opties);
  };

  await assert.rejects(rondActiverenAf({ ...omgeving, fetch: wegBijCredential }), (fout) => fout instanceof Melding && fout.cause instanceof VerbindingFout);
});

test('een 400 of een onleesbaar bewijs gaat als eigen fout door: vernieuwen helpt dan niet', async () => {
  const bij400 = await terugVanAppManager({ status: { '/credential': 400 } });

  await assert.rejects(rondActiverenAf(bij400.omgeving), { name: 'ActiverenFout', status: 400 });

  globalThis.indexedDB = new IDBFactory();

  const onleesbaar = await terugVanAppManager({ credential: 'geen credential' });

  await assert.rejects(rondActiverenAf(onleesbaar.omgeving), errors.JOSEError);
});

test('vernieuwen zonder registratie zegt dat de browser nog niet geactiveerd is', async () => {
  await assert.rejects(vernieuw({ appManager: APP_MANAGER, kluis, fetch: nepAppManager().fetch }), (fout) => fout instanceof Melding
    && fout.message.includes('nog niet geactiveerd'));
});

test('vernieuwen haalt met dezelfde registratie een vers bewijs op', async () => {
  const credential = await credentialMet({ ontvanger: 'KVK:90000014', exp: 1_800_000_000 });

  await kluis.vervangRegistratie('r1');
  await vernieuw({ appManager: APP_MANAGER, kluis, fetch: nepAppManager({ credential }).fetch });

  assert.equal(await kluis.haal(kluis.CREDENTIAL), credential);
  assert.equal(await kluis.haal(kluis.REGISTRATIE), 'r1');
});

test('kent de App Manager de registratie niet meer, dan verdwijnen registratie en bewijs', async () => {
  await kluis.vervangRegistratie('r1');
  await kluis.bewaarCredential('r1', 'oud');

  await assert.rejects(vernieuw({ appManager: APP_MANAGER, kluis, fetch: nepAppManager({ status: { '/credential': 401 } }).fetch }), (fout) => fout instanceof Melding
    && fout.message.includes('niet meer geldig'));
  assert.equal(await kluis.haal(kluis.REGISTRATIE), undefined);
  assert.equal(await kluis.haal(kluis.CREDENTIAL), undefined);
});

test('activeert een ander tabblad intussen opnieuw, dan wist een afgewezen oude registratie de nieuwe niet', async () => {
  await kluis.vervangRegistratie('r1');

  const appManager = nepAppManager({ status: { '/credential': 401 } });
  const anderTabbladTussendoor = async (adres, opties) => {
    if (new URL(adres).pathname === '/credential') await kluis.vervangRegistratie('r2');

    return appManager.fetch(adres, opties);
  };

  await assert.rejects(vernieuw({ appManager: APP_MANAGER, kluis, fetch: anderTabbladTussendoor }), (fout) => fout instanceof Melding
    && fout.message.includes('ander tabblad'));
  assert.equal(await kluis.haal(kluis.REGISTRATIE), 'r2');
});

test('een bewijs voor een registratie die intussen vervangen is, wordt niet bewaard', async () => {
  const credential = await credentialMet({ ontvanger: 'KVK:90000014', exp: 1_800_000_000 });

  await kluis.vervangRegistratie('r1');

  const appManager = nepAppManager({ credential });
  const anderTabbladTussendoor = async (adres, opties) => {
    const antwoord = await appManager.fetch(adres, opties);

    if (new URL(adres).pathname === '/credential') await kluis.vervangRegistratie('r2');

    return antwoord;
  };

  await assert.rejects(vernieuw({ appManager: APP_MANAGER, kluis, fetch: anderTabbladTussendoor }), Melding);
  assert.equal(await kluis.haal(kluis.CREDENTIAL), undefined);
});

test('wissen haalt de kluis leeg en vergeet het lopende activeren van dit tabblad', async () => {
  const sessie = nepSessie();

  await activeer({ appManager: APP_MANAGER, kluis, sessie, fetch: nepAppManager().fetch });
  await wis({ kluis, sessie });

  assert.equal(await kluis.haal(kluis.ACTIVATIE), undefined);
  assert.equal(sessie.getItem(ACTIVATIE_SESSIE), null);
});

test('de status noemt de ontvanger en zegt of het bewijs verlopen is', async () => {
  const credential = await credentialMet({ ontvanger: 'KVK:90000014', exp: 1_800_000_000 });
  const ervoor = new Date(1_799_999_999_000);
  const erna = new Date(1_800_000_000_000);

  assert.equal(statustekst(undefined, undefined), 'Deze browser is nog niet geactiveerd.');
  assert.match(statustekst('r1', undefined), /nog geen bewijs/);
  assert.match(statustekst('r1', credential, ervoor), /^Geactiveerd voor KVK:90000014\. Het bewijs is geldig tot /);
  assert.match(statustekst('r1', credential, erna), /^Geactiveerd voor KVK:90000014\. Het bewijs is verlopen/);
});

test('elke soort fout krijgt zijn eigen melding', () => {
  const gevallen = [
    [new Melding('eigen tekst'), 'eigen tekst'],
    [new VerbindingFout('weg'), /Geen verbinding/],
    [new kluis.KluisFout('nee'), OPSLAG_GEWEIGERD],
    [new ActiverenFout('x', 401), /wees het verzoek af/],
    [new ActiverenFout('x', 400), /begrijpt het verzoek van de app niet/],
    [new ActiverenFout('x', 503), /even te druk/],
    [new ActiverenFout('x', 500), /gaf een fout/],
    [new ActiverenFout('x'), /niet te lezen/],
    [new errors.JWEDecryptionFailed(), /niet te lezen/],
    [new SyntaxError('kapot'), /niet te lezen/],
    [new TypeError('bug'), /iets mis in de app/],
  ];

  for (const [fout, verwacht] of gevallen) {
    if (typeof verwacht === 'string') assert.equal(melding(fout), verwacht, fout.name);
    else assert.match(melding(fout), verwacht, fout.name);
  }
});
