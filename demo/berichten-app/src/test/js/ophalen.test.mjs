import { test } from 'node:test';
import assert from 'node:assert/strict';
import { CompactSign, errors } from 'jose';
import { ActiverenFout, VerbindingFout } from '../../main/resources/META-INF/resources/js/activeren.js';
import {
  FOUT_IN_DE_APP, GELIJKTIJDIG, MAX_BERICHTEN_PER_MAGAZIJN, MAX_MAGAZIJNEN, StelseldocumentFout, haalAllesOp, haalOpBij,
  leesStelseldocument, reden,
} from '../../main/resources/META-INF/resources/js/ophalen.js';
import { maakSleutelpaar } from '../../main/resources/META-INF/resources/js/sleutelkluis.js';
import { nepMagazijn } from './hulp.mjs';

const A = { oin: '00000009000000000001', naam: 'Magazijn A', url: 'https://a.test/magazijn/00000009000000000001' };
const B = { oin: '00000009000000000002', naam: 'Magazijn B', url: 'https://b.test' };
const C = { oin: '00000009000000000003', naam: 'Magazijn C', url: 'https://c.test' };

/** Alleen de payload telt voor `leesStelseldocument`; de handtekening toetst hij niet (TODO(#1216)). */
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

  assert.deepEqual(leesStelseldocument(jws), { magazijnen: [A, B], overgeslagen: 0 });
});

test('een organisatie met een onbruikbare OIN, naam of adres valt weg; de rest blijft', async () => {
  const jws = await stelseldocument([
    { oin: '123', name: 'Kort', magazijn_url: 'https://x.test' },
    { oin: A.oin, magazijn_url: A.url },
    { oin: `00000009000000000101`, name: B.naam, magazijn_url: 'geen url' },
    { oin: `00000009000000000102`, name: B.naam, magazijn_url: 'ftp://b.test' },
    { oin: `00000009000000000103`, name: B.naam, magazijn_url: 'https://b.test/?pad=x' },
    { oin: `00000009000000000104`, name: B.naam, magazijn_url: 'https://b.test/#x' },
    // Een array of getal laat new URL en een regex het als tekst lezen; het hoort toch weg te vallen.
    { oin: `00000009000000000105`, name: B.naam, magazijn_url: [B.url] },
    { oin: [B.oin], name: B.naam, magazijn_url: B.url },
    { oin: 9000000000000000002, name: B.naam, magazijn_url: B.url },
    // Een gebruikersnaam laat de fetch van de browser falen; een lege naam geeft een lege regel.
    { oin: `00000009000000000106`, name: B.naam, magazijn_url: 'https://beheer:geheim@b.test' },
    { oin: `00000009000000000107`, name: ' ', magazijn_url: B.url },
    { oin: `00000009000000000108`, name: B.naam, magazijn_url: `https://b.test/${'a'.repeat(2048)}` },
    null,
    { oin: C.oin, name: C.naam, magazijn_url: C.url },
  ]);

  assert.deepEqual(leesStelseldocument(jws), { magazijnen: [C], overgeslagen: 13 });
});

test('een OIN die twee keer in het stelseldocument staat, maakt het document onbruikbaar', async () => {
  const jws = await stelseldocument([
    { oin: A.oin, name: A.naam, magazijn_url: A.url },
    { oin: B.oin, name: B.naam, magazijn_url: B.url },
    { oin: A.oin, name: 'Nagemaakt', magazijn_url: 'https://elders.test' },
  ]);

  assert.throws(() => leesStelseldocument(jws), { name: 'StelseldocumentFout', message: new RegExp(A.oin) });
});

test('een adres met een lange reeks slashes en een teken erachter kost geen kwadratische tijd', () => {
  const lang = `https://a.test/${'/'.repeat(2030)}x`;
  const organisaties = Array.from({ length: MAX_MAGAZIJNEN }, (_, i) => ({ oin: String(i).padStart(20, '0'), name: `M${i}`, magazijn_url: lang }));
  const begin = Date.now();

  const { magazijnen } = leesStelseldocument(stelseldocumentZonderSleutel(organisaties));

  assert.equal(magazijnen[0].url, lang);
  // Kwadratisch kost dit bijna een seconde; lineair een paar milliseconden.
  assert.ok(Date.now() - begin < 300, `duurde ${Date.now() - begin} ms`);
});

for (const [omschrijving, org, blijft] of [
  ['een naam van precies de grens', { name: 'n'.repeat(2048), magazijn_url: 'https://b.test' }, true],
  ['een naam boven de grens', { name: 'n'.repeat(2049), magazijn_url: 'https://b.test' }, false],
  ['een adres van precies de grens', { name: 'B', magazijn_url: `https://b.test/${'a'.repeat(2048 - 15)}` }, true],
  ['een adres boven de grens', { name: 'B', magazijn_url: `https://b.test/${'a'.repeat(2049 - 15)}` }, false],
  ['alleen een gebruikersnaam in het adres', { name: 'B', magazijn_url: 'https://beheer@b.test' }, false],
  ['alleen een wachtwoord in het adres', { name: 'B', magazijn_url: 'https://:geheim@b.test' }, false],
]) {
  test(`${omschrijving}: ${blijft ? 'blijft' : 'valt weg'}`, () => {
    const { magazijnen } = leesStelseldocument(stelseldocumentZonderSleutel([{ oin: B.oin, ...org }, { oin: C.oin, name: C.naam, magazijn_url: C.url }]));

    assert.deepEqual(magazijnen.map((m) => m.oin), blijft ? [B.oin, C.oin] : [C.oin]);
  });
}

test('een dubbele OIN maakt het document onbruikbaar, ook als de eerste vermelding zelf onbruikbaar was', () => {
  const organisaties = [{ oin: A.oin, name: ' ', magazijn_url: A.url }, { oin: A.oin, name: A.naam, magazijn_url: A.url }];

  assert.throws(() => leesStelseldocument(stelseldocumentZonderSleutel(organisaties)), StelseldocumentFout);
});

test('de console noemt de OIN van elke overgeslagen organisatie, en geen andere waarde', (t) => {
  const waarschuwing = t.mock.method(console, 'warn', () => {});

  leesStelseldocument(stelseldocumentZonderSleutel([
    { oin: A.oin, name: A.naam, magazijn_url: 'geen url' },
    { oin: '999999990', name: 'BSN', magazijn_url: B.url },
    { oin: C.oin, name: C.naam, magazijn_url: C.url },
  ]));

  assert.deepEqual(waarschuwing.mock.calls[0].arguments[1], [A.oin, '(geen geldige OIN)']);
});

test('een stelseldocument waarvan geen enkele organisatie bruikbaar is, is zelf onbruikbaar', () => {
  assert.throws(() => leesStelseldocument(stelseldocumentZonderSleutel([{ oin: '1' }, null])), StelseldocumentFout);
});

test('een leeg stelseldocument levert geen magazijnen; een document zonder lijst of geen JWS is een fout', async () => {
  assert.deepEqual(leesStelseldocument(await stelseldocument([])), { magazijnen: [], overgeslagen: 0 });
  assert.throws(() => leesStelseldocument('geen jws'), StelseldocumentFout);

  const { privateKey } = await maakSleutelpaar();
  const zonderLijst = await new CompactSign(new TextEncoder().encode('{}')).setProtectedHeader({ alg: 'ES256' }).sign(privateKey);

  assert.throws(() => leesStelseldocument(zonderLijst), StelseldocumentFout);
});

for (const aantal of [0, 1, 3]) {
  test(`bij ${aantal} magazijn(en) komen alle berichten samen, nieuwste eerst`, async () => {
    const magazijnen = [A, B, C].slice(0, aantal);
    const haal = async (magazijn) => lijst([
      { onderwerp: `${magazijn.naam} oud`, publicatietijdstip: `2026-10-0${magazijnen.indexOf(magazijn) + 1}T10:00:00Z` },
      { onderwerp: `${magazijn.naam} nieuw`, publicatietijdstip: `2026-10-2${magazijnen.indexOf(magazijn) + 1}T10:00:00Z` },
    ]);

    const { berichten, mislukt } = await haalAllesOp(magazijnen, {}, haal);
    const tijden = berichten.map((b) => b.publicatietijdstip);

    assert.equal(berichten.length, aantal * 2);
    assert.deepEqual(tijden, [...tijden].sort().reverse());
    assert.deepEqual(mislukt, []);
  });
}

test('een magazijn dat faalt, houdt de rest niet op en wordt bij naam genoemd', async () => {
  const fout = new ActiverenFout('sessies mislukt (HTTP 401)', 401, 'sessies');
  const haal = async (magazijn) => {
    if (magazijn === B) throw fout;

    return lijst([{ onderwerp: magazijn.naam, publicatietijdstip: '2026-10-27T10:00:00Z', magazijn: magazijn.naam }]);
  };

  const { berichten, mislukt } = await haalAllesOp([A, B, C], {}, haal);

  assert.deepEqual(berichten.map((b) => b.magazijn), ['Magazijn A', 'Magazijn C']);
  assert.deepEqual(mislukt.map(({ naam, reden: r }) => [naam, r]), [['Magazijn B', 'weigerde het bewijs']]);
  assert.equal(mislukt[0].oorzaak, fout);
});

test('de volgorde gaat op het moment, ook met een fractie of offset; zonder tijdstip achteraan', async () => {
  const haal = async () => lijst([
    { onderwerp: 'zonder' },
    { onderwerp: 'tien uur', publicatietijdstip: '2026-10-27T10:00:00Z' },
    { onderwerp: 'half elf, in offset', publicatietijdstip: '2026-10-27T12:30:00+02:00' },
    { onderwerp: 'tien uur en een halve seconde', publicatietijdstip: '2026-10-27T10:00:00.500Z' },
    { onderwerp: 'half tien, in offset', publicatietijdstip: '2026-10-27T11:30:00+02:00' },
  ]);

  const { berichten } = await haalAllesOp([A], {}, haal);

  assert.deepEqual(berichten.map((b) => b.onderwerp), ['half elf, in offset', 'tien uur en een halve seconde', 'tien uur', 'half tien, in offset', 'zonder']);
});

test('als alle magazijnen falen, komt elk met zijn reden terug en zijn er geen berichten', async () => {
  const haal = async (magazijn) => {
    throw magazijn === A ? new ActiverenFout('x', 401, 'sessies') : new VerbindingFout('weg', { cause: new TypeError('fetch failed') });
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

    return lijst([]);
  };

  const { mislukt } = await haalAllesOp(magazijnen, {}, haal);

  assert.deepEqual(mislukt, []);
  assert.equal(hoogste, GELIJKTIJDIG);
});

for (const [aantal, toegestaan] of [[MAX_MAGAZIJNEN, true], [MAX_MAGAZIJNEN + 1, false]]) {
  test(`een stelseldocument met ${aantal} organisaties wordt ${toegestaan ? 'gelezen' : 'geweigerd'}`, () => {
    const organisaties = Array.from({ length: aantal }, (_, i) => ({ oin: String(i).padStart(20, '0'), name: `M${i}`, magazijn_url: 'https://m.test' }));
    const lees = () => leesStelseldocument(stelseldocumentZonderSleutel(organisaties));

    if (toegestaan) assert.equal(lees().magazijnen.length, aantal); else assert.throws(lees, StelseldocumentFout);
  });
}

test('de grens geldt voor de organisaties in het document, ook de onbruikbare', () => {
  const organisaties = [
    ...Array.from({ length: MAX_MAGAZIJNEN }, () => ({ oin: 'onbruikbaar' })),
    { oin: A.oin, name: A.naam, magazijn_url: A.url },
  ];

  assert.throws(() => leesStelseldocument(stelseldocumentZonderSleutel(organisaties)), StelseldocumentFout);
});

test('de reden is een korte Nederlandse tekst per soort fout', () => {
  const timeout = new DOMException('te laat', 'TimeoutError');

  assert.equal(reden(new VerbindingFout('weg', { cause: timeout })), 'antwoordde niet op tijd');
  assert.equal(reden(new VerbindingFout('weg', { cause: new TypeError('fetch failed') })), 'niet bereikbaar');
  assert.equal(reden(new ActiverenFout('x', 503)), 'tijdelijk niet beschikbaar');
  assert.equal(reden(new ActiverenFout('x', 404)), 'gaf een fout (404)');
  assert.equal(reden(new errors.JWEInvalid('kapot')), 'gaf een onbruikbaar antwoord');
  assert.equal(reden(new ActiverenFout('onverwachte berichtenlijst', undefined, 'berichten')), 'gaf een onbruikbaar antwoord');
  assert.equal(reden(new ActiverenFout('x', 401, 'sessies')), 'weigerde het bewijs');
  assert.equal(reden(new ActiverenFout('x', 401, 'berichten')), 'nam de sessie niet aan');
  assert.equal(reden(new ActiverenFout('x', 401, 'challenges')), 'gaf een fout (401)');
  assert.equal(reden(new TypeError("Cannot read properties of undefined (reading 'privateKey')")), FOUT_IN_DE_APP);
});

test('een magazijn dat blijft hangen, telt na de timeout als niet op tijd', async () => {
  const hangt = (adres, opties) => new Promise((gelukt, mislukt) => {
    // De timer van AbortSignal.timeout houdt de event loop niet vast; zonder deze stopt Node 22 het
    // testproces voordat de abort komt.
    const vast = setTimeout(() => {}, 10_000);

    opties.signal.addEventListener('abort', () => {
      clearTimeout(vast);
      mislukt(opties.signal.reason);
    });
  });

  const opties = { sleutelpaar: await maakSleutelpaar(), credential: 'x', fetch: hangt, timeoutMs: 30 };

  const begin = Date.now();
  const { mislukt } = await haalAllesOp([A], opties);

  assert.equal(mislukt[0].reden, 'antwoordde niet op tijd');
  // De opgegeven timeout, niet de standaard van tien seconden.
  assert.ok(Date.now() - begin < 2_000, `duurde ${Date.now() - begin} ms`);
  await assert.rejects(haalOpBij(A, opties), VerbindingFout);
});

test('het ophalen gaat naar de tweede ingang onder het adres uit het stelseldocument', async () => {
  const adressen = [];
  const fetch = async (adres) => {
    adressen.push(String(adres));

    return new Response('', { status: 503 });
  };

  await assert.rejects(haalOpBij(A, { sleutelpaar: await maakSleutelpaar(), credential: 'x', fetch }), { status: 503 });
  assert.deepEqual(adressen, [`${A.url}/api/v1/rechtstreeks/challenges`]);
});

test('bij een magazijn: challenge, sessie met het credential, en de lijst met het token als Bearer', async () => {
  const sleutelpaar = await maakSleutelpaar();
  const magazijn = nepMagazijn([{ onderwerp: 'Aanslag', publicatietijdstip: '2026-10-27T10:00:00Z' }]);

  const uitkomst = await haalOpBij(A, { sleutelpaar, credential: 'het-credential', fetch: magazijn.fetch });

  assert.deepEqual(uitkomst, { berichten: [{ onderwerp: 'Aanslag', publicatietijdstip: '2026-10-27T10:00:00Z', magazijn: A.naam }], totaal: 1 });
  assert.equal(new URL(magazijn.log.lijstAdressen[0]).searchParams.get('pageSize'), String(MAX_BERICHTEN_PER_MAGAZIJN));
  assert.equal(magazijn.log.presentaties[0].certificate_type, 'app_manager_jwt_certificate');
  assert.equal(magazijn.log.presentaties[0].certificate, 'het-credential');
  assert.match(magazijn.log.autorisaties[0], /^Bearer token1x+$/);
});

test('een sessie-antwoord met het veld van de App Manager in plaats van sessieToken is onbruikbaar', async () => {
  const magazijn = nepMagazijn([], { sessieAntwoord: (token) => ({ session_token: token, expires_in: 900 }) });

  await assert.rejects(
    haalOpBij(A, { sleutelpaar: await maakSleutelpaar(), credential: 'c', fetch: magazijn.fetch }),
    { name: 'ActiverenFout', stap: 'sessies' },
  );
  assert.deepEqual(magazijn.log.autorisaties, []);
});

for (const [aantal, gelukt] of [[MAX_BERICHTEN_PER_MAGAZIJN, true], [MAX_BERICHTEN_PER_MAGAZIJN + 1, false]]) {
  test(`een lijst van ${aantal} berichten is ${gelukt ? 'bruikbaar' : 'onbruikbaar'}, en de andere magazijnen tellen gewoon mee`, async () => {
    const berichten = Array.from({ length: aantal }, (_, i) => ({ onderwerp: `B${i}`, publicatietijdstip: '2026-10-27T10:00:00Z' }));
    const groot = nepMagazijn(berichten);
    const klein = nepMagazijn([{ onderwerp: 'Gewoon', publicatietijdstip: '2026-10-27T11:00:00Z' }]);
    const fetch = (adres, opties) => (String(adres).startsWith(A.url) ? groot : klein).fetch(adres, opties);

    const { berichten: samen, mislukt } = await haalAllesOp([A, B], { sleutelpaar: await maakSleutelpaar(), credential: 'c', fetch });

    assert.equal(samen.length, gelukt ? aantal + 1 : 1);
    assert.deepEqual(mislukt.map((m) => [m.oin, m.reden]), gelukt ? [] : [[A.oin, 'gaf een onbruikbaar antwoord']]);
  });
}

test('een ontsleuteld antwoord dat geen JSON is, geeft een fout zonder de inhoud als oorzaak', async () => {
  const magazijn = nepMagazijn([], { sessieAntwoord: () => '{"sessieToken":"BSN:999999990",]' });

  const fout = await haalOpBij(A, { sleutelpaar: await maakSleutelpaar(), credential: 'c', fetch: magazijn.fetch }).catch((f) => f);

  assert.equal(fout.name, 'ActiverenFout');
  assert.equal(fout.stap, 'sessies');
  assert.equal(fout.cause, undefined);
  assert.doesNotMatch(fout.message, /999999990/);
});

/** De uitkomst van één magazijn zoals haalOpBij hem geeft. */
function lijst(berichten, totaal = berichten.length) {
  return { berichten, totaal };
}

test('een lijst die geen JSON is, geeft een fout zonder de ontsleutelde inhoud als oorzaak', async () => {
  const magazijn = nepMagazijn([], { lijstAntwoord: () => '{"berichten":[{"onderwerp":"BSN 999999990"}],' });

  const fout = await haalOpBij(A, { sleutelpaar: await maakSleutelpaar(), credential: 'c', fetch: magazijn.fetch }).catch((f) => f);

  assert.equal(fout.stap, 'berichten');
  assert.equal(fout.cause, undefined);
  assert.doesNotMatch(fout.message, /999999990/);
});

for (const [omstandigheid, kapot] of [
  ['een challenge die geen JWS is', (adres) => adres.endsWith('/challenges')],
  ['een lijst die geen JWE is', (adres) => adres.includes('/rechtstreeks/berichten')],
]) {
  test(`${omstandigheid} is een onbruikbaar antwoord van het magazijn, geen fout in de app`, async () => {
    const magazijn = nepMagazijn([]);
    const fetch = (adres, opties) => (kapot(String(adres)) ? Promise.resolve(new Response('a.b.c.d.e', { status: 201 })) : magazijn.fetch(adres, opties));

    const { mislukt } = await haalAllesOp([A], { sleutelpaar: await maakSleutelpaar(), credential: 'c', fetch });

    assert.equal(mislukt[0].reden, 'gaf een onbruikbaar antwoord');
  });
}

test('de timeout geldt per verzoek: drie trage verzoeken die elk op tijd zijn, lukken samen', async () => {
  const magazijn = nepMagazijn([{ onderwerp: 'Traag', publicatietijdstip: '2026-10-27T10:00:00Z' }]);
  // Samen boven één timeout, elk eronder, en samen ruim onder het totaal.
  const traag = async (adres, opties) => {
    await new Promise((klaar, weg) => {
      setTimeout(klaar, 110);
      opties.signal.addEventListener('abort', () => weg(opties.signal.reason));
    });

    return magazijn.fetch(adres, opties);
  };

  const { berichten, mislukt } = await haalAllesOp([A], { sleutelpaar: await maakSleutelpaar(), credential: 'c', fetch: traag, timeoutMs: 200 });

  assert.deepEqual(mislukt, []);
  assert.equal(berichten.length, 1);
});

test('voor de drie verzoeken samen geldt een ruimere grens', async () => {
  const magazijn = nepMagazijn([]);
  const traag = async (adres, opties) => {
    await new Promise((klaar, weg) => {
      setTimeout(klaar, 90);
      opties.signal.addEventListener('abort', () => weg(opties.signal.reason));
    });

    return magazijn.fetch(adres, opties);
  };

  const { mislukt } = await haalAllesOp([A], { sleutelpaar: await maakSleutelpaar(), credential: 'c', fetch: traag, timeoutMs: 100 });

  assert.equal(mislukt[0]?.reden, 'antwoordde niet op tijd');
});

test('heeft een magazijn meer berichten dan de app ophaalt, dan staat dat in de uitkomst', async () => {
  const magazijn = nepMagazijn([{ onderwerp: 'Eerste', publicatietijdstip: '2026-10-27T10:00:00Z' }], {
    lijstAntwoord: (lijstje) => ({ berichten: lijstje, totalElements: 250 }),
  });

  const { berichten, afgekapt } = await haalAllesOp([A], { sleutelpaar: await maakSleutelpaar(), credential: 'c', fetch: magazijn.fetch });

  assert.equal(berichten.length, 1);
  assert.deepEqual(afgekapt.map(({ oin, getoond, totaal }) => [oin, getoond, totaal]), [[A.oin, 1, 250]]);
});

for (const token of ['', 'een token', 'tök€n', 'een\ttoken', 'een\ntoken']) {
  test(`een sessietoken ${JSON.stringify(token)} is een onbruikbaar antwoord en geen onbereikbaar magazijn`, async () => {
    const magazijn = nepMagazijn([], { sessieAntwoord: () => ({ sessieToken: token, geldigTot: '2026-10-27T10:15:00Z' }) });

    const { mislukt } = await haalAllesOp([A], { sleutelpaar: await maakSleutelpaar(), credential: 'c', fetch: magazijn.fetch });

    assert.equal(mislukt[0].reden, 'gaf een onbruikbaar antwoord');
    assert.deepEqual(magazijn.log.autorisaties, []);
  });
}

test('één verzoek boven de timeout valt af, ook als het totaal nog niet op is', async () => {
  const magazijn = nepMagazijn([]);
  const eersteTraag = async (adres, opties) => {
    if (String(adres).endsWith('/challenges')) {
      await new Promise((klaar, weg) => {
        setTimeout(klaar, 150);
        opties.signal.addEventListener('abort', () => weg(opties.signal.reason));
      });
    }

    return magazijn.fetch(adres, opties);
  };

  const { mislukt } = await haalAllesOp([A], { sleutelpaar: await maakSleutelpaar(), credential: 'c', fetch: eersteTraag, timeoutMs: 100 });

  assert.equal(mislukt[0]?.reden, 'antwoordde niet op tijd');
});

for (const aantalAfgekapt of [0, 1, 2]) {
  test(`bij ${aantalAfgekapt} afgekapte magazijn(en) noemt de uitkomst precies die`, async () => {
    const magazijnen = [A, B, C];
    const nep = new Map(magazijnen.map((m, i) => [m.url, nepMagazijn([{ onderwerp: m.naam, publicatietijdstip: '2026-10-27T10:00:00Z' }], {
      lijstAntwoord: (l) => ({ berichten: l, totalElements: i >= magazijnen.length - aantalAfgekapt ? 250 : l.length }),
    })]));
    const fetch = (adres, opties) => nep.get([...nep.keys()].find((url) => String(adres).startsWith(url))).fetch(adres, opties);

    const { afgekapt } = await haalAllesOp(magazijnen, { sleutelpaar: await maakSleutelpaar(), credential: 'c', fetch });

    assert.deepEqual(afgekapt.map((m) => m.oin), magazijnen.slice(magazijnen.length - aantalAfgekapt).map((m) => m.oin));
  });
}

for (const totalElements of ['250', 1.5, null, undefined]) {
  test(`een onbruikbare totalElements (${String(totalElements)}) telt als een volledige lijst`, async () => {
    const magazijn = nepMagazijn([{ onderwerp: 'A', publicatietijdstip: '2026-10-27T10:00:00Z' }], { lijstAntwoord: (l) => ({ berichten: l, totalElements }) });

    const { berichten, afgekapt, mislukt } = await haalAllesOp([A], { sleutelpaar: await maakSleutelpaar(), credential: 'c', fetch: magazijn.fetch });

    assert.equal(berichten.length, 1);
    assert.deepEqual(afgekapt, []);
    assert.deepEqual(mislukt, []);
  });
}
