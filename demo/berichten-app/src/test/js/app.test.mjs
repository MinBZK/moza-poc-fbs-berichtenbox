// De bedrading van app.js met een nep-DOM, fake-indexeddb en een nep-App Manager. app.js start bij het
// importeren; elke test laadt een eigen exemplaar (`?n=…`) met eigen globals.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { IDBFactory } from 'fake-indexeddb';
import * as kluis from '../../main/resources/META-INF/resources/js/sleutelkluis.js';
import { activeer } from '../../main/resources/META-INF/resources/js/verloop.js';
import {
  BASIS, OIN, credentialMet, nepAppManager, nepMagazijn, onderteken, registratiebericht, registreer,
} from './hulp.mjs';

const CONFIG = { appManager: { url: BASIS, oin: OIN }, stelselregister: { url: 'https://stelselregister.test/' } };
const APP = new URL('../../main/resources/META-INF/resources/js/app.js', import.meta.url);
const echteSetTimeout = globalThis.setTimeout;
let exemplaar = 0;

function element(id) {
  const luisteraars = {};

  return {
    id,
    disabled: false,
    hidden: false,
    textContent: '',
    kinderen: [],
    replaceChildren(...kinderen) { this.kinderen = kinderen; },
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
async function laadApp({
  config = CONFIG, hash = '', appManager = nepAppManager(), sessie = nepSessie(), overig = async () => new Response('', { status: 404 }),
} = {}) {
  const elementen = Object.fromEntries(
    ['status', 'fout', 'activeren', 'vernieuwen', 'wissen', 'ophalen', 'overzicht', 'berichten', 'niet-bereikt', 'mislukt'].map((id) => [id, element(id)]),
  );
  const venster = {};
  const tabblad = { elementen, venster, vertrokkenNaar: [], timers: [], gewist: [], sessie };

  Object.assign(globalThis, {
    document: {
      documentElement: { dataset: {} },
      getElementById: (id) => elementen[id] ?? null,
      createElement: (soort) => ({ soort, textContent: '' }),
    },
    isSecureContext: true,
    sessionStorage: sessie,
    location: { hash, pathname: '/', search: '', assign: (adres) => tabblad.vertrokkenNaar.push(adres) },
    history: { replaceState() {} },
    addEventListener: (type, functie) => { venster[type] = functie; },
    confirm: () => true,
    fetch: async (adres, opties) => {
      if (adres === 'config.json') return typeof config === 'function' ? config() : Response.json(config);

      return String(adres).startsWith(BASIS) ? appManager.fetch(adres, opties) : overig(adres, opties);
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
  ['instellingen zonder OIN', { ...CONFIG, appManager: { url: BASIS } }],
  ['instellingen zonder stelselregister', { appManager: CONFIG.appManager }],
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

/** Een kluis met een registratie en een credential dat verloopt op [exp] (seconden sinds 1970). */
async function geactiveerd(exp) {
  globalThis.indexedDB = new IDBFactory();
  await registreer(kluis, 'r1');
  assert.equal(await kluis.bewaarCredential('r1', await credentialMet({ ontvanger: 'KVK:90000014', exp })), true);
}

for (const [omstandigheid, exp, aan] of [['zonder bewijs', undefined, false], ['met een verlopen bewijs', 1, false], ['met een geldig bewijs', 4_000_000_000, true]]) {
  test(`Berichten ophalen is ${aan ? 'aan' : 'uit'} ${omstandigheid}`, async () => {
    if (exp) await geactiveerd(exp); else globalThis.indexedDB = new IDBFactory();

    const tabblad = await laadApp();

    await wacht(() => knoppenAan(tabblad), 'knoppen na het laden');
    assert.equal(tabblad.elementen.ophalen.disabled, !aan);
  });
}

test('Berichten ophalen toont de lijst met datum, magazijn en onderwerp, en noemt het magazijn dat niet antwoordde', async () => {
  await geactiveerd(4_000_000_000);

  const magazijn = nepMagazijn([{ onderwerp: 'Aanslag', publicatietijdstip: '2026-10-27T10:00:00Z' }]);
  const organizations = [
    { oin: '00000009000000000001', name: 'Magazijn A', magazijn_url: 'https://a.test' },
    { oin: '00000009000000000002', name: 'Magazijn B', magazijn_url: 'https://b.test' },
  ];
  const verzoeken = [];
  const overig = async (adres, opties) => {
    verzoeken.push(String(adres));

    if (String(adres) === 'https://stelselregister.test/api/v1/stelseldocument') return new Response(await onderteken({ organizations }));

    return String(adres).startsWith('https://a.test/') ? magazijn.fetch(adres, opties) : new Response('', { status: 503 });
  };

  const tabblad = await laadApp({ overig });

  await wacht(() => !tabblad.elementen.ophalen.disabled, 'ophaalknop aan');
  tabblad.elementen.ophalen.klik();
  await wacht(() => tabblad.elementen.overzicht.textContent !== '', 'overzicht');

  assert.equal(tabblad.elementen.overzicht.textContent, '1 bericht(en) uit 1 van de 2 magazijnen.');
  assert.deepEqual(tabblad.elementen.berichten.kinderen.map((li) => li.textContent.split(' · ').slice(1)), [['Magazijn A', 'Aanslag']]);
  assert.deepEqual(tabblad.elementen.mislukt.kinderen.map((li) => li.textContent), ['Magazijn B: tijdelijk niet beschikbaar']);
  assert.equal(tabblad.elementen['niet-bereikt'].hidden, false);
  assert.equal(tabblad.elementen.fout.textContent, '');
});

test('na Activering wissen verdwijnt een opgehaalde lijst', async () => {
  await geactiveerd(4_000_000_000);

  const magazijn = nepMagazijn([{ onderwerp: 'Aanslag', publicatietijdstip: '2026-10-27T10:00:00Z' }]);
  const organizations = [{ oin: '00000009000000000001', name: 'Magazijn A', magazijn_url: 'https://a.test' }];
  const overig = async (adres, opties) => (String(adres).includes('stelselregister')
    ? new Response(await onderteken({ organizations }))
    : magazijn.fetch(adres, opties));

  const tabblad = await laadApp({ overig });

  await wacht(() => !tabblad.elementen.ophalen.disabled, 'ophaalknop aan');
  tabblad.elementen.ophalen.klik();
  await wacht(() => tabblad.elementen.berichten.kinderen.length === 1, 'lijst');

  tabblad.elementen.wissen.klik();
  await wacht(() => tabblad.elementen.berichten.kinderen.length === 0, 'lijst leeg na wissen');

  assert.equal(tabblad.elementen.overzicht.textContent, '');
  assert.equal(tabblad.elementen.ophalen.disabled, true);
});

for (const [omstandigheid, stelselregister, verwacht] of [
  ['niet bereikbaar', async () => { throw new TypeError('fetch failed'); }, /stelselregister is niet te bereiken/],
  ['met een HTTP-fout', async () => new Response('', { status: 503 }), /HTTP 503/],
  ['met een onleesbaar document', async () => new Response('geen jws'), /lijst van magazijnen is niet te lezen/],
]) {
  test(`is het stelselregister ${omstandigheid}, dan zegt de pagina het en blijft de lijst leeg`, async () => {
    await geactiveerd(4_000_000_000);

    const tabblad = await laadApp({ overig: stelselregister });

    await wacht(() => !tabblad.elementen.ophalen.disabled, 'ophaalknop aan');
    tabblad.elementen.ophalen.klik();
    await wacht(() => tabblad.elementen.fout.textContent !== '', 'foutmelding');

    assert.match(tabblad.elementen.fout.textContent, verwacht);
    assert.deepEqual(tabblad.elementen.berichten.kinderen, []);
  });
}
