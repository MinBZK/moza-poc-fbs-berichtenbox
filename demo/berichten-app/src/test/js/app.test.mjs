// De bedrading van app.js met een nep-DOM, fake-indexeddb en een nep-App Manager. app.js start bij het
// importeren; elke test laadt een eigen exemplaar (`?n=…`) met eigen globals.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { IDBFactory } from 'fake-indexeddb';
import * as kluis from '../../main/resources/META-INF/resources/js/sleutelkluis.js';
import { activeer } from '../../main/resources/META-INF/resources/js/verloop.js';
import {
  BASIS, OIN, credentialMet, nepAppManager, registratiebericht,
} from './hulp.mjs';

const APP = new URL('../../main/resources/META-INF/resources/js/app.js', import.meta.url);
const echteSetTimeout = globalThis.setTimeout;
let exemplaar = 0;

function element(id) {
  const luisteraars = {};

  return {
    id,
    disabled: false,
    textContent: '',
    addEventListener: (type, functie) => { luisteraars[type] = functie; },
    focus() {},
    klik: () => luisteraars.click?.(),
  };
}

function nepSessie() {
  const waarden = new Map();

  return {
    getItem: (naam) => waarden.get(naam) ?? null,
    setItem: (naam, waarde) => waarden.set(naam, String(waarde)),
    removeItem: (naam) => waarden.delete(naam),
  };
}

/**
 * Zet de globals van een browsertabblad en laadt app.js. [config] is de inhoud van config.json, of
 * een functie die het hele antwoord maakt (een Response is maar één keer te lezen);
 * de time-out na het vertrek (15 s) wordt vastgehouden in plaats van afgewacht.
 */
async function laadApp({ config = { appManager: { url: BASIS, oin: OIN } }, hash = '', appManager = nepAppManager(), sessie = nepSessie() } = {}) {
  const elementen = Object.fromEntries(['status', 'fout', 'activeren', 'vernieuwen', 'wissen'].map((id) => [id, element(id)]));
  const venster = {};
  const tabblad = { elementen, venster, vertrokkenNaar: [], timers: [], gewist: [], sessie };

  Object.assign(globalThis, {
    document: { documentElement: { dataset: {} }, getElementById: (id) => elementen[id] ?? null },
    isSecureContext: true,
    sessionStorage: sessie,
    location: { hash, pathname: '/', search: '', assign: (adres) => tabblad.vertrokkenNaar.push(adres) },
    history: { replaceState() {} },
    addEventListener: (type, functie) => { venster[type] = functie; },
    confirm: () => true,
    fetch: async (adres, opties) => {
      if (adres === 'config.json') return typeof config === 'function' ? config() : Response.json(config);

      return appManager.fetch(adres, opties);
    },
    setTimeout: (functie, ms) => {
      if (ms !== 15_000) return echteSetTimeout(functie, ms);

      tabblad.timers.push(functie);

      return tabblad.timers.length;
    },
    clearTimeout: (id) => tabblad.gewist.push(id),
  });

  exemplaar += 1;
  await import(`${APP.href}?n=${exemplaar}`);

  return tabblad;
}

async function wacht(voorwaarde, wat) {
  for (let poging = 0; poging < 200; poging += 1) {
    if (await voorwaarde()) return;

    await new Promise((klaar) => { echteSetTimeout(klaar, 5); });
  }

  assert.fail(`niet gebeurd: ${wat}`);
}

const knoppenAan = ({ elementen }) => ['activeren', 'wissen'].every((id) => !elementen[id].disabled);

test('met een fragment in het adres rondt de pagina het activeren af en toont de ontvanger', async () => {
  globalThis.indexedDB = new IDBFactory();

  const credential = await credentialMet({ ontvanger: 'KVK:90000014', exp: 4_000_000_000 });
  const appManager = nepAppManager({ credential });
  const sessie = nepSessie();

  await activeer({ appManager: { url: BASIS, oin: OIN }, kluis, sessie, fetch: appManager.fetch });

  const hash = await registratiebericht({ sub: 'registration_token', iss: BASIS, registration_token: 'r1' }, appManager.sessieSleutel());
  const tabblad = await laadApp({ hash, appManager, sessie });

  await wacht(() => tabblad.elementen.status.textContent.startsWith('Geactiveerd voor KVK:90000014'), 'status na afronden');
  assert.equal(await kluis.haal(kluis.CREDENTIAL), credential);
  assert.equal(tabblad.elementen.fout.textContent, '');
  assert.equal(tabblad.elementen.vernieuwen.disabled, false);
});

test('na Activeren blijven de knoppen uit tot de navigatie weg is, en komen terug als hij niet doorgaat', async () => {
  globalThis.indexedDB = new IDBFactory();

  const tabblad = await laadApp();

  await wacht(() => knoppenAan(tabblad), 'knoppen na het laden');
  assert.equal(tabblad.elementen.vernieuwen.disabled, true);

  tabblad.elementen.activeren.klik();
  await wacht(() => tabblad.vertrokkenNaar.length === 1, 'vertrek naar de App Manager');

  assert.equal(tabblad.vertrokkenNaar[0], `${BASIS}register_app?session_token=sessie-1`);
  assert.equal(tabblad.elementen.activeren.disabled, true);
  assert.equal(tabblad.timers.length, 1);

  // De navigatie gaat niet door (Esc): na de time-out zijn de knoppen terug.
  tabblad.timers[0]();
  await wacht(() => knoppenAan(tabblad), 'knoppen na een afgebroken navigatie');

  // Gaat hij wel door, dan ruimt pagehide de time-out op.
  tabblad.elementen.activeren.klik();
  await wacht(() => tabblad.vertrokkenNaar.length === 2, 'tweede vertrek');
  tabblad.venster.pagehide();

  assert.deepEqual(tabblad.gewist, [2]);
});

test('terug met Vorige uit de cache zet de knoppen weer aan; een gewone pageshow niet', async () => {
  globalThis.indexedDB = new IDBFactory();

  const tabblad = await laadApp();

  await wacht(() => knoppenAan(tabblad), 'knoppen na het laden');
  tabblad.elementen.activeren.klik();
  await wacht(() => tabblad.vertrokkenNaar.length === 1, 'vertrek');

  tabblad.venster.pageshow({ persisted: false });
  await new Promise((klaar) => { echteSetTimeout(klaar, 50); });
  assert.equal(tabblad.elementen.activeren.disabled, true);

  tabblad.venster.pageshow({ persisted: true });
  await wacht(() => knoppenAan(tabblad), 'knoppen na terugkomst uit de cache');
});

for (const [omstandigheid, config] of [
  ['een HTTP-fout', () => new Response('', { status: 500 })],
  ['instellingen zonder OIN', { appManager: { url: BASIS } }],
  ['geen JSON', () => new Response('<html>', { status: 200 })],
]) {
  for (const metFragment of [false, true]) {
    test(`bij ${omstandigheid} blijven de knoppen uit en zegt de pagina het${metFragment ? ', met opnieuw activeren' : ''}`, async () => {
      globalThis.indexedDB = new IDBFactory();

      const tabblad = await laadApp({ config, hash: metFragment ? '#registration_token=x' : '' });

      await wacht(() => tabblad.elementen.fout.textContent !== '', 'foutmelding');
      assert.match(tabblad.elementen.fout.textContent, /instellingen niet laden/);
      assert.equal(/opnieuw activeren/.test(tabblad.elementen.fout.textContent), metFragment);
      assert.ok(['activeren', 'vernieuwen', 'wissen'].every((id) => tabblad.elementen[id].disabled));
    });
  }
}

test('weigert de browser opslag, dan blijven de knoppen uit en zegt de status waarom', async () => {
  globalThis.indexedDB = { open() { throw new DOMException('verboden', 'SecurityError'); } };

  const tabblad = await laadApp();

  await wacht(() => /geen opslag toe/.test(tabblad.elementen.status.textContent), 'status zonder opslag');
  assert.ok(['activeren', 'vernieuwen', 'wissen'].every((id) => tabblad.elementen[id].disabled));
});
