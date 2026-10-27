import { test } from 'node:test';
import assert from 'node:assert/strict';
import { CompactSign } from '../../main/resources/META-INF/resources/vendor/jose-6.2.13/index.js';
import { ActiverenFout, VerbindingFout } from '../../main/resources/META-INF/resources/js/activeren.js';
import {
  StelseldocumentFout, haalAllesOp, haalOpBij, leesStelseldocument, reden,
} from '../../main/resources/META-INF/resources/js/ophalen.js';
import { maakSleutelpaar } from '../../main/resources/META-INF/resources/js/sleutelkluis.js';

const A = { oin: '00000009000000000001', naam: 'Magazijn A', url: 'https://a.test/magazijn/00000009000000000001' };
const B = { oin: '00000009000000000002', naam: 'Magazijn B', url: 'https://b.test' };
const C = { oin: '00000009000000000003', naam: 'Magazijn C', url: 'https://c.test' };

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
  test(`bij ${aantal} magazijn(en) komen alle berichten samen, nieuwste eerst, met de naam van het magazijn`, async () => {
    const magazijnen = [A, B, C].slice(0, aantal);
    const haal = async (magazijn) => [
      { onderwerp: `${magazijn.naam} oud`, publicatietijdstip: `2026-10-0${magazijnen.indexOf(magazijn) + 1}T10:00:00Z`, magazijn: magazijn.naam },
      { onderwerp: `${magazijn.naam} nieuw`, publicatietijdstip: `2026-10-2${magazijnen.indexOf(magazijn) + 1}T10:00:00Z`, magazijn: magazijn.naam },
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
