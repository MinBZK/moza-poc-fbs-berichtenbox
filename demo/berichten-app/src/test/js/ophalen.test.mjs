import { test } from 'node:test';
import assert from 'node:assert/strict';
import { CompactSign } from '../../main/resources/META-INF/resources/vendor/jose-6.2.13/index.js';
import { ActiverenFout, VerbindingFout } from '../../main/resources/META-INF/resources/js/activeren.js';
import {
  GELIJKTIJDIG, MAX_MAGAZIJNEN, StelseldocumentFout, haalAllesOp, haalOpBij, leesStelseldocument, reden,
} from '../../main/resources/META-INF/resources/js/ophalen.js';
import { maakSleutelpaar } from '../../main/resources/META-INF/resources/js/sleutelkluis.js';

const A = { oin: '00000009000000000001', naam: 'Magazijn A', url: 'https://a.test/magazijn/00000009000000000001' };
const B = { oin: '00000009000000000002', naam: 'Magazijn B', url: 'https://b.test' };
const C = { oin: '00000009000000000003', naam: 'Magazijn C', url: 'https://c.test' };

/** Alleen de payload telt voor `leesStelseldocument`; de handtekening wordt nog niet getoetst. */
function stelseldocumentZonderSleutel(organizations) {
  const deel = (waarde) => Buffer.from(JSON.stringify(waarde)).toString('base64url');

  return `${deel({ alg: 'ES256' })}.${deel({ organizations })}.handtekening`;
}

async function stelseldocument(organizations) {
  const { privateKey } = await maakSleutelpaar();

  return new CompactSign(new TextEncoder().encode(JSON.stringify({ organizations })))
    .setProtectedHeader({ alg: 'ES256' })
    .sign(privateKey);
}

test('het stelseldocument levert de magazijnen, zonder slash aan het eind van het adres', async () => {
  const jws = await stelseldocument([
    { oin: A.oin, name: A.naam, magazijn_url: `${A.url}/` },
    { oin: B.oin, name: B.naam, magazijn_url: B.url },
  ]);

  assert.deepEqual(leesStelseldocument(jws), [A, B]);
});

test('een organisatie met een onbruikbare OIN, naam of adres valt weg; de rest blijft', async () => {
  const jws = await stelseldocument([
    { oin: '123', name: 'Kort', magazijn_url: 'https://x.test' },
    { oin: A.oin, magazijn_url: A.url },
    { oin: B.oin, name: B.naam, magazijn_url: 'geen url' },
    { oin: B.oin, name: B.naam, magazijn_url: 'ftp://b.test' },
    { oin: B.oin, name: B.naam, magazijn_url: 'https://b.test/?pad=x' },
    { oin: B.oin, name: B.naam, magazijn_url: 'https://b.test/#x' },
    null,
    { oin: C.oin, name: C.naam, magazijn_url: C.url },
  ]);

  assert.deepEqual(leesStelseldocument(jws), [C]);
});

test('een leeg stelseldocument levert geen magazijnen; een document zonder lijst of geen JWS is een fout', async () => {
  assert.deepEqual(leesStelseldocument(await stelseldocument([])), []);
  assert.throws(() => leesStelseldocument('geen jws'), StelseldocumentFout);

  const { privateKey } = await maakSleutelpaar();
  const zonderLijst = await new CompactSign(new TextEncoder().encode('{}')).setProtectedHeader({ alg: 'ES256' }).sign(privateKey);

  assert.throws(() => leesStelseldocument(zonderLijst), StelseldocumentFout);
});

for (const aantal of [0, 1, 3]) {
  test(`bij ${aantal} magazijn(en) komen alle berichten samen, nieuwste eerst`, async () => {
    const magazijnen = [A, B, C].slice(0, aantal);
    const haal = async (magazijn) => [
      { onderwerp: `${magazijn.naam} oud`, publicatietijdstip: `2026-10-0${magazijnen.indexOf(magazijn) + 1}T10:00:00Z` },
      { onderwerp: `${magazijn.naam} nieuw`, publicatietijdstip: `2026-10-2${magazijnen.indexOf(magazijn) + 1}T10:00:00Z` },
    ];

    const { berichten, mislukt } = await haalAllesOp(magazijnen, {}, haal);
    const tijden = berichten.map((b) => b.publicatietijdstip);

    assert.equal(berichten.length, aantal * 2);
    assert.deepEqual(tijden, [...tijden].sort().reverse());
    assert.deepEqual(mislukt, []);
  });
}

test('een magazijn dat faalt, houdt de rest niet op en wordt bij naam genoemd', async () => {
  const fout = new ActiverenFout('credential mislukt (HTTP 401)', 401, 'complete');
  const haal = async (magazijn) => {
    if (magazijn === B) throw fout;

    return [{ onderwerp: magazijn.naam, publicatietijdstip: '2026-10-27T10:00:00Z', magazijn: magazijn.naam }];
  };

  const { berichten, mislukt } = await haalAllesOp([A, B, C], {}, haal);

  assert.deepEqual(berichten.map((b) => b.magazijn), ['Magazijn A', 'Magazijn C']);
  assert.deepEqual(mislukt.map(({ naam, reden: r }) => [naam, r]), [['Magazijn B', 'weigerde het bewijs']]);
  assert.equal(mislukt[0].oorzaak, fout);
});

test('de volgorde gaat op het moment, ook met een fractie of offset; zonder tijdstip achteraan', async () => {
  const haal = async () => [
    { onderwerp: 'zonder' },
    { onderwerp: 'tien uur', publicatietijdstip: '2026-10-27T10:00:00Z' },
    { onderwerp: 'half elf, in offset', publicatietijdstip: '2026-10-27T12:30:00+02:00' },
    { onderwerp: 'tien uur en een halve seconde', publicatietijdstip: '2026-10-27T10:00:00.500Z' },
    { onderwerp: 'half tien, in offset', publicatietijdstip: '2026-10-27T11:30:00+02:00' },
  ];

  const { berichten } = await haalAllesOp([A], {}, haal);

  assert.deepEqual(berichten.map((b) => b.onderwerp), ['half elf, in offset', 'tien uur en een halve seconde', 'tien uur', 'half tien, in offset', 'zonder']);
});

test('als alle magazijnen falen, komt elk met zijn reden terug en zijn er geen berichten', async () => {
  const haal = async (magazijn) => {
    throw magazijn === A ? new ActiverenFout('x', 401) : new VerbindingFout('weg', { cause: new TypeError('fetch failed') });
  };

  const { berichten, mislukt } = await haalAllesOp([A, B, C], {}, haal);

  assert.deepEqual(berichten, []);
  assert.deepEqual(mislukt.map((m) => [m.oin, m.reden]), [[A.oin, 'weigerde het bewijs'], [B.oin, 'niet bereikbaar'], [C.oin, 'niet bereikbaar']]);
});

test('nooit meer dan het vaste aantal magazijnen tegelijk', async () => {
  const magazijnen = Array.from({ length: 20 }, (_, i) => ({ ...A, oin: String(i).padStart(20, '0') }));
  let bezig = 0;
  let hoogste = 0;
  const haal = async () => {
    bezig += 1;
    hoogste = Math.max(hoogste, bezig);
    await new Promise((klaar) => setTimeout(klaar, 5));
    bezig -= 1;

    return [];
  };

  const { mislukt } = await haalAllesOp(magazijnen, {}, haal);

  assert.deepEqual(mislukt, []);
  assert.equal(hoogste, GELIJKTIJDIG);
});

test('een stelseldocument met meer dan het maximum aan magazijnen wordt geweigerd', async () => {
  const organisaties = Array.from({ length: MAX_MAGAZIJNEN + 1 }, (_, i) => ({ oin: String(i).padStart(20, '0'), name: `M${i}`, magazijn_url: 'https://m.test' }));

  assert.throws(() => leesStelseldocument(stelseldocumentZonderSleutel(organisaties)), StelseldocumentFout);
});

test('de reden is een korte Nederlandse tekst per soort fout', () => {
  const timeout = new DOMException('te laat', 'TimeoutError');

  assert.equal(reden(new VerbindingFout('weg', { cause: timeout })), 'antwoordde niet op tijd');
  assert.equal(reden(new VerbindingFout('weg', { cause: new TypeError('fetch failed') })), 'niet bereikbaar');
  assert.equal(reden(new ActiverenFout('x', 503)), 'tijdelijk niet beschikbaar');
  assert.equal(reden(new ActiverenFout('x', 404)), 'gaf een fout (404)');
  assert.equal(reden(new SyntaxError('kapot')), 'gaf een onbruikbaar antwoord');
});

test('een magazijn dat blijft hangen, telt na de wachttijd als niet op tijd', async () => {
  const hangt = (adres, opties) => new Promise((gelukt, mislukt) => {
    opties.signal.addEventListener('abort', () => mislukt(opties.signal.reason));
  });

  const opties = { sleutelpaar: await maakSleutelpaar(), credential: 'x', fetch: hangt, wachttijdMs: 30 };

  const { mislukt } = await haalAllesOp([A], opties);

  assert.equal(mislukt[0].reden, 'antwoordde niet op tijd');
  await assert.rejects(haalOpBij(A, opties), VerbindingFout);
});

test('het ophalen gaat naar de tweede ingang onder het adres uit het stelseldocument', async () => {
  const adressen = [];
  const fetch = async (adres) => {
    adressen.push(String(adres));

    return new Response('', { status: 503 });
  };

  await assert.rejects(haalOpBij(A, { sleutelpaar: await maakSleutelpaar(), credential: 'x', fetch }), { status: 503 });
  assert.deepEqual(adressen, [`${A.url}/api/v1/rechtstreeks/challenge`]);
});
