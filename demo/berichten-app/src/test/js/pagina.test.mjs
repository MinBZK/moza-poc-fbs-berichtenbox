// De twee scripts van de pagina, met alleen de stukjes DOM die ze aanraken. app.js start bij het
// importeren, dus één keer per testbestand.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const JS = new URL('../../main/resources/META-INF/resources/js/', import.meta.url);

function nepDocument() {
  const elementen = Object.fromEntries(['status', 'fout', 'activeren', 'vernieuwen', 'wissen'].map((id) => [id, { id, textContent: '' }]));
  const luisteraars = {};
  const moduleScript = { addEventListener: (type, functie) => { luisteraars[`script:${type}`] = functie; } };

  return {
    elementen,
    luisteraars,
    document: {
      documentElement: { dataset: {} },
      getElementById: (id) => elementen[id] ?? null,
      querySelector: (selector) => (selector === 'script[type="module"]' ? moduleScript : null),
    },
  };
}

test('app.js zet het teken voor het vangnet en haalt het token uit de geschiedenis, ook in een ongeschikte browser', async () => {
  const { document, elementen } = nepDocument();
  const vervangen = [];

  Object.assign(globalThis, {
    document,
    isSecureContext: false,
    location: { hash: '#registration_token=geheim', pathname: '/', search: '' },
    history: { replaceState: (...argumenten) => vervangen.push(argumenten) },
  });

  await import(new URL('app.js', JS));

  assert.equal(document.documentElement.dataset.gestart, 'ja');
  assert.deepEqual(vervangen, [[null, '', '/']]);
  assert.match(elementen.status.textContent, /geen veilige opslag of cryptografie/);
});

/** Draait vangnet.js met een nep-document; geeft de luisteraars en de geplande time-out terug. */
async function vangnet(document) {
  const bron = await readFile(new URL('vangnet.js', JS), 'utf8');
  const venster = {};
  let tijdslimiet;

  new Function('document', 'addEventListener', 'setTimeout', bron)(
    document,
    (type, functie) => { venster[type] = functie; },
    (functie, ms) => { tijdslimiet = { functie, ms }; },
  );

  return { venster, tijdslimiet };
}

for (const [oorzaak, vuur] of [
  ['een bestand dat niet laadt', ({ luisteraars }) => luisteraars['script:error']()],
  ['een bestand dat niet te parsen is', (_, venster) => venster.error()],
  ['de time-out', (_, __, tijdslimiet) => tijdslimiet.functie()],
]) {
  test(`het vangnet meldt ${oorzaak} als de app niet gestart is`, async () => {
    const nep = nepDocument();
    const { venster, tijdslimiet } = await vangnet(nep.document);

    vuur(nep, venster, tijdslimiet);

    assert.match(nep.elementen.status.textContent, /De app kon niet laden/);
  });
}

test('het vangnet zwijgt als de app gestart is, en wacht ruim op een trage verbinding', async () => {
  const nep = nepDocument();
  const { venster, tijdslimiet } = await vangnet(nep.document);

  nep.document.documentElement.dataset.gestart = 'ja';
  nep.elementen.status.textContent = 'Geactiveerd';
  nep.luisteraars['script:error']();
  venster.error();
  tijdslimiet.functie();

  assert.equal(nep.elementen.status.textContent, 'Geactiveerd');
  assert.ok(tijdslimiet.ms >= 15_000, `time-out van ${tijdslimiet.ms} ms`);
});
