/* Bediening van de demo-stack. Los van index.html zodat de opmaak leesbaar blijft: elke actieknop
 * draagt zijn methode en pad als data-attribuut, en één listener op het document voert ze uit. */

/* Waar de berichtenbox staat. Dit pad klopt lokaal: daar zet de demo-proxy de proeftuin en dit
 * paneel achter één origin. Op een gedeelde omgeving draagt elk component zijn eigen hostnaam en
 * levert /api/demo/omgeving de volledige URL. */
const BOX_PAD = '/moza/berichtenbox/';

let boxUrl = BOX_PAD;

/* Waar je gebleven was, zodat een refresh je niet terugzet op het eerste tabblad met het paneel
 * open over de berichtenbox heen. In sessionStorage en niet in localStorage: dit overleeft een
 * refresh maar niet het sluiten van het tabblad, zodat een volgende demo schoon begint in plaats
 * van stilzwijgend de instellingen van de vorige te erven.
 *
 * De prefix is geen sierlijkheid: via de demo-proxy staan de proeftuin en dit paneel op dezelfde
 * origin, en delen ze dus dezelfde storage. */
const STAND_SLEUTEL = 'fbs-demo-bediening:stand';

const VELDEN = ['aantal', 'tempoInterval', 'actiefAantal', 'ontdubbelPersona'];
const POLL_MS = 5000;
const UITKOMST_MS = 4000;

/* Sleutels zoals de API ze gebruikt, namen zoals ze in de demo genoemd worden. Alleen hier: de
 * knoppen en de statusbalk mogen niet ieder hun eigen vertaling verzinnen. */
const MAGAZIJN_NAMEN = {
    'magazijn-a': 'RVO',
    'magazijn-b': 'Bel.dienst',
};

/* Loopt er een actie, dan slaat de poll over — de statusbalk zou anders de toestand van halverwege
 * een herstel tonen. */
let bezig = false;

/* Volgnummer per ververs-ronde. Een poll die al onderweg was toen je klikte, mag de verse toestand
 * van ná die actie niet overschrijven. */
let ververslus = 0;

/* Of deze omgeving gesimuleerde magazijnen kent, uit /api/demo/omgeving; null zolang dat nog niet
 * gelezen is. Uit de configuratie en niet uit een geslaagde uitlezing: anders is "niet ingericht"
 * niet te onderscheiden van "niet kunnen lezen", en verdwijnt de chip juist wanneer er iets stuk
 * is. */
let heeftSimulator = null;

const melding = document.getElementById('melding');
const meldingTekst = document.getElementById('melding-tekst');
const meldingRuw = document.getElementById('melding-ruw');
const meldingJson = document.getElementById('melding-json');

// ---------------------------------------------------------------- waar je gebleven was

/* Storage kan gooien wanneer site-data geblokkeerd is, en de bewaarde waarde kan onleesbaar zijn.
 * Het paneel moet dan gewoon werken, alleen zonder geheugen. */
function leesStand() {
    try {
        return JSON.parse(sessionStorage.getItem(STAND_SLEUTEL)) || {};
    } catch (fout) {
        return {};
    }
}

function bewaarStand(wijziging) {
    try {
        sessionStorage.setItem(STAND_SLEUTEL, JSON.stringify(Object.assign(leesStand(), wijziging)));
    } catch (fout) {
        return;
    }
}

function bewaarVelden() {
    const velden = {};

    VELDEN.forEach((id) => {
        const veld = document.getElementById(id);

        if (veld) velden[id] = veld.value;
    });

    bewaarStand({ velden: velden });
}

/* Keuzelijsten worden pas na een netwerkaanroep gevuld en herstellen zichzelf daar, zodra ze
 * weten welke opties er zijn. */
function herstelStand() {
    const stand = leesStand();
    const velden = stand.velden || {};

    if (stand.ingeklapt) klap();

    const tab = stand.tab ? document.getElementById(stand.tab) : null;

    if (tab && tab.getAttribute('role') === 'tab') kiesTab(tab);

    VELDEN.forEach((id) => {
        const veld = document.getElementById(id);

        if (veld && veld.tagName !== 'SELECT' && velden[id]) veld.value = velden[id];
    });
}

// ---------------------------------------------------------------- berichtenbox in het frame

/* Zonder de demo-proxy bestaat het eigen pad niet op deze origin en toont het frame een
 * 404-pagina. Een frame dat mis laadt geeft geen gebeurtenis die JavaScript van een geslaagde kan
 * onderscheiden, dus toetsen we dat pad vooraf in plaats van achteraf te raden.
 *
 * Een geconfigureerd adres nemen we juist op zijn woord: dat wijst naar een ander component, en
 * daar strandt een HEAD op CORS. Die uitkomst is niet van onbereikbaar te onderscheiden, dus
 * toetsen zou de berichtenbox altijd verbergen — precies waar hij wél staat. */
function bepaalBox(url) {
    boxUrl = url || BOX_PAD;

    if (url) {
        toonBox(true);

        return;
    }

    fetch(boxUrl, { method: 'HEAD' })
        .then((respons) => toonBox(respons.ok))
        .catch(() => toonBox(false));
}

function toonBox(bereikbaar) {
    const box = document.getElementById('box');

    box.hidden = !bereikbaar;
    document.getElementById('geen-box').hidden = bereikbaar;

    if (bereikbaar) box.src = boxUrl;
}

/* Bewust een knop en niet automatisch na elke actie: een herlaad zet de berichtenbox terug op zijn
 * beginstand, en midden in een demo bepaal je zelf wanneer dat mag. Op dezelfde origin kan dat
 * gericht, zonder dat de proeftuin daar iets voor hoeft te bouwen; staat de berichtenbox op een
 * eigen hostnaam, dan weigert de browser die toegang en zet het opnieuw zetten van `src` hem
 * alsnog terug. */
function verversBox() {
    const box = document.getElementById('box');

    if (box.hidden) return;

    try {
        box.contentWindow.location.reload();
    } catch (fout) {
        box.src = boxUrl;
    }
}

/* De richting van het teken zegt wat er gebeurt — » duwt de bediening weg, « haalt hem terug. */
function klap() {
    const ingeklapt = document.body.classList.toggle('ingeklapt');
    const knop = document.getElementById('klap');
    const bijschrift = ingeklapt ? 'Bediening tonen' : 'Bediening verbergen';

    knop.textContent = ingeklapt ? '«' : '»';
    knop.setAttribute('aria-expanded', String(!ingeklapt));
    knop.setAttribute('aria-label', bijschrift);
    knop.title = bijschrift;

    bewaarStand({ ingeklapt: ingeklapt });
}

// ---------------------------------------------------------------- melding

function toonMelding(tekst, soort, ruw) {
    melding.hidden = false;
    melding.className = 'melding' + (soort ? ' melding--' + soort : '');
    meldingTekst.textContent = tekst;
    meldingRuw.hidden = !ruw;

    // Bij twijfel staat het antwoord meteen open: dan is de ruwe JSON het enige aanknopingspunt,
    // en tijdens een demo klapt niemand een <details> uit.
    meldingRuw.open = Boolean(ruw) && soort === 'let-op';
    meldingJson.textContent = ruw || '';
}

function alsJson(waarde) {
    return JSON.stringify(waarde, null, 2);
}

function naam(sleutel) {
    return MAGAZIJN_NAMEN[sleutel] || sleutel;
}

/* Tijdens een demo moet één regel volstaan om te zien wat er gebeurd is. De ruwe JSON blijft
 * bereikbaar onder de melding; wat hier ontbreekt valt terug op die JSON in plaats van op een
 * verzonnen zin. */
const SAMENVATTINGEN = {
    berichten: (body) =>
        'Berichten — ' + Object.entries(body).map(([sleutel, aantal]) => naam(sleutel) + ' ' + aantal).join(', '),

    vulling: (body) => vullingTekst(body),

    herstel: (body) =>
        'Hersteld. Geleegd: ' +
        Object.entries(body.geleegd).map(([sleutel, aantal]) => naam(sleutel) + ' ' + aantal).join(', ') +
        '. ' + vullingTekst(body.vulling) +
        '. Gesimuleerd: ' + body.gesimuleerd.berichten + ' weg, ' + body.gesimuleerdGevuld + ' klaargezet',

    status: (body) => body.status,

    tempo: (body) =>
        body.loopt
            ? 'Stroom loopt: elke ' + body.intervalSeconden + ' s, ' + body.geleverd + ' geleverd'
            : 'Stroom staat uit',

    storingen: (body) => storingenTekst(body),

    simulator: (body) => body.actief + ' van ' + body.totaal + ' gesimuleerde magazijnen zonder storing',

    'simulator-vullen': (body) =>
        body.berichten + ' berichten en ' + body.bijlagen + ' bijlagen klaargezet in ' +
        body.magazijnen + ' gesimuleerde magazijnen' +
        (body.overgeslagen ? ', ' + body.overgeslagen + ' stonden er al' : ''),

    'simulator-legen': (body) =>
        body.berichten + ' berichten weg; ' + body.magazijnen + ' gesimuleerde magazijnen terug op hun gedrag',

    'simulator-magazijnen': (body) =>
        body.length + ' gesimuleerde magazijnen: ' +
        Object.entries(body.reduce((telling, magazijn) => {
            telling[magazijn.modus] = (telling[magazijn.modus] || 0) + 1;

            return telling;
        }, {})).map(([modus, aantal]) => aantal + '× ' + modus.toLowerCase()).join(', '),

    sessie: (body) => body.gewisteKeys + ' sessie-key(s) gewist; de volgende uitvraag geeft 409',

    'foutieve-aanlevering': (body) => 'Het magazijn wees de aanlevering af met HTTP ' + body.status,

    ontdubbeling: (body) =>
        'Event ' + body.eventId + ' tweemaal aangeboden: HTTP ' + body.eersteStatus +
        ' en HTTP ' + body.tweedeStatus,

    omgeving: (body) =>
        'Uitvraag: ' + (body.uitvraagBasis || 'afgeleid uit de browser') +
        '. Storingsknoppen: ' + (body.storingen.join(', ') || 'geen'),

    personas: (body) => body.length + " persona's: " + body.map((persona) => persona.label).join(', '),
};

function vullingTekst(vulling) {
    let tekst = vulling.geslaagd + ' van ' + vulling.aangeboden + ' berichten aangeleverd';

    if (vulling.mislukt) tekst += ', ' + vulling.mislukt + ' mislukt';

    if (vulling.markeringMislukt) tekst += ', ' + vulling.markeringMislukt + ' niet op gelezen gezet';

    return tekst;
}

function storingenTekst(storingen) {
    const afwijkend = Object.entries(storingen).filter(([, toestand]) => toestand !== 'normaal');

    if (!afwijkend.length) return 'Geen storingen: alles staat normaal';

    return 'Storing: ' + afwijkend.map(([proxy, toestand]) => naam(proxy) + ' ' + toestand).join(', ');
}

function samenvatting(soort, body) {
    const formatter = SAMENVATTINGEN[soort];

    if (!formatter) return { tekst: null, soort: 'goed' };

    try {
        return { tekst: formatter(body), soort: vullingSoort(body) };
    } catch (fout) {
        // Een antwoord in een andere vorm dan verwacht is zelf een signaal: als gewoon "Gelukt"
        // tonen laat een keten die iets anders teruggeeft er gezond uitzien.
        return { tekst: 'Geslaagd, maar het antwoord had een onverwachte vorm — zie de JSON hieronder', soort: 'let-op' };
    }
}

/* HTTP 200 zegt alleen dat de console het verzoek verwerkte, niet dat de berichten aankwamen. Een
 * groene melding boven "100 mislukt" is het verkeerde signaal. */
function vullingSoort(body) {
    const vulling = body && body.vulling ? body.vulling : body;

    if (!vulling || typeof vulling.aangeboden !== 'number') return 'goed';

    if (vulling.aangeboden > 0 && vulling.geslaagd === 0) return 'fout';

    return vulling.mislukt || vulling.markeringMislukt ? 'let-op' : 'goed';
}

// ---------------------------------------------------------------- acties uitvoeren

/* Vier uitkomsten die tijdens een demo verschillend moeten lezen: geen verbinding, antwoord
 * afgebroken, onleesbaar antwoord, en geweigerd met de melding uit de body. De body wordt eerst als
 * tekst gelezen zodat een niet-JSON foutpagina een leesbare melding oplevert in plaats van een
 * SyntaxError. */
async function roep(pad, methode) {
    let respons;

    try {
        respons = await fetch(pad, { method: methode });
    } catch (fout) {
        return { gelukt: false, tekst: 'Geen verbinding met de demo-console: ' + fout, ruw: null };
    }

    // fetch() lost al op zodra de headers binnen zijn; het lezen van de body kan daarna alsnog
    // afbreken — precies bij de trage knoppen, in een demo over een wankele verbinding.
    let tekst;

    try {
        tekst = await respons.text();
    } catch (fout) {
        return { gelukt: false, tekst: 'Antwoord afgebroken (HTTP ' + respons.status + '): ' + fout, ruw: null };
    }

    let body;

    try {
        body = JSON.parse(tekst);
    } catch (fout) {
        return {
            gelukt: false,
            tekst: 'Onleesbaar antwoord (HTTP ' + respons.status + ')',
            ruw: tekst || '(lege body)',
        };
    }

    if (!respons.ok) {
        return {
            gelukt: false,
            tekst: 'Mislukt (HTTP ' + respons.status + '): ' + (body.fout || tekst),
            ruw: alsJson(body),
        };
    }

    return { gelukt: true, body: body, ruw: alsJson(body) };
}

/* Een `{veldnaam}` in het pad komt uit het invoerveld met die id. Ongeldige invoer wordt hier
 * gestopt en niet bij de server: de browser wijst dan het veld zelf aan. */
function vulPadIn(pad) {
    let ontbreekt = false;

    const ingevuld = pad.replace(/\{(\w+)\}/g, (heel, id) => {
        const veld = document.getElementById(id);

        if (!veld || !veld.checkValidity() || veld.value === '') {
            if (veld) veld.reportValidity();

            ontbreekt = true;

            return '';
        }

        return encodeURIComponent(veld.value);
    });

    return ontbreekt ? null : ingevuld;
}

/* De uitkomst blijft even in de knop zelf staan. Dat is het antwoord op "heb ik hem nou
 * ingedrukt?": de melding bovenaan zegt wát er gebeurde, dit merkteken zegt wélke knop het deed. */
function zetUitkomst(knop, uitkomst) {
    clearTimeout(Number(knop.dataset.uitkomstTimer));

    knop.dataset.uitkomst = uitkomst;

    if (uitkomst === 'bezig') return;

    knop.dataset.uitkomstTimer = String(
        setTimeout(() => {
            delete knop.dataset.uitkomst;
        }, UITKOMST_MS),
    );
}

async function voerUit(knop) {
    const pad = vulPadIn(knop.dataset.pad);

    if (pad === null) return;

    bezig = true;
    knop.disabled = true;
    zetUitkomst(knop, 'bezig');
    toonMelding('Bezig…', null, null);

    // Alles opruimen in een finally: een bezig-vlag die blijft hangen zet de poll stil, en dan
    // toont de balk de rest van de sessie verouderde waarden zonder dat iets dat verraadt.
    try {
        const uitkomst = await roep(pad, knop.dataset.methode);

        zetUitkomst(knop, uitkomst.gelukt ? 'gelukt' : 'mislukt');

        if (uitkomst.gelukt) {
            const samengevat = samenvatting(knop.dataset.samenvatting, uitkomst.body);

            toonMelding(samengevat.tekst || 'Gelukt', samengevat.soort, uitkomst.ruw);
        } else {
            toonMelding(uitkomst.tekst, 'fout', uitkomst.ruw);
        }
    } catch (fout) {
        zetUitkomst(knop, 'mislukt');
        toonMelding('Onverwachte fout in het paneel: ' + fout, 'fout', null);
    } finally {
        knop.disabled = false;

        // Een knop die tijdens de actie op disabled ging, verliest de focus naar <body>. Alleen
        // teruggeven als hij daar nog staat, zodat we hem niet weghalen bij wie intussen verder
        // getabd is.
        if (document.activeElement === document.body) knop.focus();

        bezig = false;

        verversToestand();
    }
}

// ---------------------------------------------------------------- bevestiging

/* In het paneel zelf en niet via confirm(): die dialoog valt buiten het scherm dat je deelt, en
 * dwingt bovendien tot één tekst voor knoppen die heel verschillende dingen doen. */
function sluitBevestiging(terugNaar) {
    document.querySelectorAll('.bevestig').forEach((blok) => blok.remove());

    // De focus stond op een knop die we net weghalen; zonder dit valt hij terug op <body> en
    // begint toetsenbordnavigatie weer bovenaan de pagina.
    if (terugNaar) terugNaar.focus();
}

let bevestigTeller = 0;

function vraagBevestiging(knop) {
    sluitBevestiging();

    const blok = document.createElement('div');
    const vraag = document.createElement('p');
    const knoppen = document.createElement('div');
    const ja = document.createElement('button');
    const nee = document.createElement('button');
    const vraagId = 'bevestig-vraag-' + ++bevestigTeller;

    // alertdialog met een verwijzing naar de vraag: anders hoort een schermlezer alleen "Nee, laat
    // staan" en nooit de zin die zegt wát er precies weggaat.
    blok.className = 'bevestig';
    blok.setAttribute('role', 'alertdialog');
    blok.setAttribute('aria-labelledby', vraagId);
    vraag.className = 'bevestig__vraag';
    vraag.id = vraagId;
    vraag.textContent = knop.dataset.bevestig + ' Doorgaan?';
    knoppen.className = 'bevestig__knoppen';

    nee.type = 'button';
    nee.className = 'knop';
    nee.textContent = 'Nee, laat staan';
    nee.addEventListener('click', () => sluitBevestiging(knop));

    ja.type = 'button';
    ja.className = 'knop knop--gevaar';
    ja.textContent = 'Ja, doorgaan';
    ja.addEventListener('click', () => {
        sluitBevestiging(knop);
        voerUit(knop);
    });

    blok.addEventListener('keydown', (gebeurtenis) => {
        if (gebeurtenis.key === 'Escape') sluitBevestiging(knop);
    });

    knoppen.append(nee, ja);
    blok.append(vraag, knoppen);
    knop.closest('.knoppen').insertBefore(blok, knop.nextSibling);

    // Focus op de veilige keuze: de gebruiker kwam hier met een klik of een Enter, en een tweede
    // aanslag mag niet ongewild een destructieve actie uitvoeren.
    nee.focus();
}

// ---------------------------------------------------------------- tabbladen

function tabs() {
    return Array.from(document.querySelectorAll('[role="tab"]'));
}

function kiesTab(gekozen) {
    tabs().forEach((tab) => {
        const actief = tab === gekozen;

        tab.setAttribute('aria-selected', String(actief));
        tab.tabIndex = actief ? 0 : -1;
        document.getElementById(tab.getAttribute('aria-controls')).hidden = !actief;
    });

    sluitBevestiging(null);
    bewaarStand({ tab: gekozen.id });
}

function tabToets(gebeurtenis) {
    const alle = tabs();
    const huidig = alle.indexOf(document.activeElement);

    if (huidig < 0) return;

    const stappen = { ArrowRight: 1, ArrowLeft: -1 };

    let doel = null;

    if (gebeurtenis.key in stappen) {
        doel = alle[(huidig + stappen[gebeurtenis.key] + alle.length) % alle.length];
    } else if (gebeurtenis.key === 'Home') {
        doel = alle[0];
    } else if (gebeurtenis.key === 'End') {
        doel = alle[alle.length - 1];
    }

    if (!doel) return;

    gebeurtenis.preventDefault();
    kiesTab(doel);
    doel.focus();
}

/* De stip is een kleurvlek; het aria-label draagt dezelfde boodschap in tekst. Het begint met het
 * zichtbare label, zodat spraakbediening op "Storingen" blijft werken. */
function markeerTab(id, letOp) {
    const tab = document.getElementById(id);

    tab.dataset.letOp = String(letOp);

    if (letOp) tab.setAttribute('aria-label', tab.dataset.label + ' — er staat iets aan');
    else tab.removeAttribute('aria-label');
}

// ---------------------------------------------------------------- toestandsbalk

/* De chips maken van null een zichtbare "onbekend", maar de reden staat alleen in het antwoord van
 * de console — en die draagt de exacte oorzaak in zijn body. Zonder deze regel heeft een bediener
 * die "onbekend" ziet geen enkel aanknopingspunt. */
async function lees(pad) {
    try {
        const respons = await fetch(pad);

        if (respons.ok) return await respons.json();

        console.error('toestand niet te lezen:', pad, respons.status, await respons.text());
    } catch (fout) {
        console.error('toestand niet te lezen:', pad, fout);
    }

    return null;
}

function zetChip(id, tekst, soort) {
    const chip = document.getElementById(id);
    const label = chip.firstElementChild;

    chip.className = 'chip' + (soort ? ' chip--' + soort : '');
    chip.textContent = ' ' + tekst;
    chip.prepend(label);
}

function toonBerichten(status) {
    if (!status) return zetChip('chip-berichten', 'onbekend', 'let-op');

    const tekst = Object.entries(status).map(([sleutel, aantal]) => naam(sleutel) + ' ' + aantal).join(' · ');

    zetChip('chip-berichten', tekst || 'geen magazijnen', null);
}

function toonStroom(tempo) {
    if (!tempo) {
        zetChip('chip-stroom', 'onbekend', 'let-op');
        markeerTab('tab-demo', true);

        return;
    }

    if (tempo.loopt) {
        zetChip('chip-stroom', 'elke ' + tempo.intervalSeconden + ' s · ' + tempo.geleverd, 'let-op');
    } else {
        zetChip('chip-stroom', 'uit', null);
    }

    markeerTab('tab-demo', tempo.loopt);
}

function toonStoringen(storingen) {
    if (!storingen) {
        zetChip('chip-storingen', 'onbekend', 'let-op');
        markeerTab('tab-storingen', true);

        return;
    }

    // Nul geconfigureerde proxies is iets anders dan nul storingen: er wordt dan niets bewaakt, en
    // "geen storingen" in het groen is daar een geruststelling die nergens op slaat.
    if (!Object.keys(storingen).length) {
        zetChip('chip-storingen', 'niet ingericht', null);
        markeerTab('tab-storingen', false);

        return;
    }

    const afwijkend = Object.entries(storingen).filter(([, toestand]) => toestand !== 'normaal');

    if (!afwijkend.length) {
        zetChip('chip-storingen', 'geen', 'goed');
    } else {
        zetChip('chip-storingen', afwijkend.map(([proxy, toestand]) => naam(proxy) + ' ' + toestand).join(' · '), 'fout');
    }

    markeerTab('tab-storingen', afwijkend.length > 0);
}

function toonMagazijnen(veel) {
    // Verbergen mag alleen op gezag van de configuratie. Verbergen omdat de uitlezing mislukte zou
    // van een storing een omgeving-zonder-simulator maken — visueel niet te onderscheiden.
    if (heeftSimulator !== true) return;

    document.getElementById('chip-magazijnen').hidden = false;

    if (!veel) {
        zetChip('chip-magazijnen', 'onbekend', 'let-op');
        markeerTab('tab-scenarios', true);

        return;
    }

    const beperkt = veel.actief < veel.totaal;

    zetChip('chip-magazijnen', veel.actief + '/' + veel.totaal, beperkt ? 'let-op' : null);
    markeerTab('tab-scenarios', beperkt);
}

/* Elke uitlezing valt apart terug: een endpoint dat niet antwoordt maakt alleen zijn eigen chip
 * onbekend, want juist bij een storing wil je de overige tellingen nog zien. */
async function verversToestand() {
    if (bezig) return;

    const beurt = ++ververslus;

    const [status, tempo, storingen, veel] = await Promise.all([
        lees('/api/demo/status'),
        lees('/api/demo/tempo'),
        lees('/api/demo/storing'),
        // Niet vragen naar wat deze omgeving niet heeft: dat levert elke vijf seconden een fout in
        // het log op, zonder dat er iets te tonen valt.
        heeftSimulator === false ? null : lees('/api/demo/simulator'),
    ]);

    // Een ronde die al liep toen er geklikt werd, mag de verse toestand van ná die actie niet
    // terugdraaien.
    if (beurt !== ververslus) return;

    toonBerichten(status);
    toonStroom(tempo);
    toonStoringen(storingen);
    toonMagazijnen(veel);
}

// ---------------------------------------------------------------- omgeving en persona's

/* Een proxy die deze omgeving niet aanbiedt krijgt geen knop: een knop die gegarandeerd een 400
 * geeft kost tijdens een demo uitleg die niets toevoegt. Console onbereikbaar: laat alles staan —
 * knoppen die zichtbaar falen zijn beter dan een leeg tabblad zonder uitleg. */
async function pasOmgevingToe() {
    const omgeving = await lees('/api/demo/omgeving');

    heeftSimulator = omgeving ? omgeving.simulator : true;

    // Pas hierna, want het adres van de berichtenbox komt uit ditzelfde antwoord. Is de console
    // onbereikbaar, dan blijft het eigen pad over — lokaal is dat het juiste adres.
    bepaalBox(omgeving ? omgeving.berichtenboxUrl : '');

    if (omgeving) {
        const beschikbaar = new Set(omgeving.storingen);

        document.querySelectorAll('button[data-proxy]').forEach((knop) => {
            knop.hidden = !beschikbaar.has(knop.dataset.proxy);
        });

        // De reset-groep hangt niet aan één proxy maar valt met de andere weg: reset() weigert een
        // leeg register, dus zonder proxies is ook die knop een gegarandeerde fout.
        document.querySelectorAll('.groep[data-groep]').forEach((groep) => {
            const knoppen = Array.from(groep.querySelectorAll('button[data-proxy]'));

            groep.hidden = beschikbaar.size === 0 || (knoppen.length > 0 && knoppen.every((knop) => knop.hidden));
        });

        document.getElementById('geen-storingen').hidden = beschikbaar.size > 0;

        // De sessiecache staat op een gedeelde omgeving in een ander project dan de console; zonder
        // netwerkregel daarheen faalt die knop gegarandeerd.
        document.getElementById('groep-sessie').hidden = omgeving.sessiecache === false;

        // Zonder simulator faalt elke knop in die groep gegarandeerd; een knop die alleen een fout
        // oplevert kost tijdens een demo uitleg die niets toevoegt. Los daarvan de uitlees-knop op
        // het info-blad: die deelt zijn groep met knoppen die er wél altijd zijn, dus hij hangt aan
        // zijn eigen markering in plaats van aan de groep.
        document.getElementById('groep-simulator').hidden = omgeving.simulator === false;

        document.querySelectorAll('button[data-simulator]').forEach((knop) => {
            knop.hidden = omgeving.simulator === false;
        });
    }

    // Pas nu weet de balk of de magazijnen-chip bestaat; zonder deze ronde blijft hij tot de
    // volgende poll leeg.
    verversToestand();
}

/* De ontdubbeling loopt op een BSN, dus alleen persona's met een BSN kunnen hem spelen. Een vrij
 * tekstveld zou een BSN vragen die verderop in dezelfde pagina al als keuzelijst bestaat. */
async function vulPersonas() {
    const keuze = document.getElementById('ontdubbelPersona');
    const knop = document.querySelector('button[data-samenvatting="ontdubbeling"]');
    const personas = await lees('/api/demo/personas');

    keuze.replaceChildren();

    // De lijst niet kunnen lezen is iets anders dan niets ingericht hebben: met de verkeerde reden
    // afhaken stuurt de bediener de configuratie in terwijl de console even weg was. De knop blijft
    // daarom aan — die faalt dan zichtbaar met de echte fout.
    if (personas === null) {
        const onbekend = document.createElement('option');

        onbekend.textContent = 'persona-lijst niet op te halen';
        keuze.append(onbekend);
        keuze.disabled = true;

        return;
    }

    const metBsn = personas.filter((persona) => persona.ontvanger.startsWith('BSN:'));

    if (!metBsn.length) {
        const leeg = document.createElement('option');

        leeg.textContent = 'geen persona met een BSN ingericht';
        keuze.append(leeg);
        keuze.disabled = true;
        knop.disabled = true;

        return;
    }

    metBsn.forEach((persona) => {
        const optie = document.createElement('option');

        optie.value = persona.ontvanger.slice('BSN:'.length);
        optie.textContent = persona.label;
        keuze.append(optie);
    });

    const bewaard = (leesStand().velden || {}).ontdubbelPersona;

    // Een persona die er niet meer is — andere configuratie, andere personaset — valt terug op
    // de eerste in de lijst in plaats van op een lege keuze die de knop laat falen.
    if (bewaard && Array.from(keuze.options).some((optie) => optie.value === bewaard)) {
        keuze.value = bewaard;
    }
}

// ---------------------------------------------------------------- bedrading

const LOSSE_ACTIES = {
    klap: klap,
    'ververs-box': verversBox,
    'ververs-toestand': verversToestand,
};

document.addEventListener('click', (gebeurtenis) => {
    const tab = gebeurtenis.target.closest('[role="tab"]');

    if (tab) {
        kiesTab(tab);

        return;
    }

    const knop = gebeurtenis.target.closest('button[data-actie], button[data-pad]');

    if (!knop) return;

    if (knop.dataset.actie) {
        LOSSE_ACTIES[knop.dataset.actie]();
    } else if (knop.dataset.bevestig) {
        vraagBevestiging(knop);
    } else {
        voerUit(knop);
    }
});

document.querySelectorAll('[role="tab"]').forEach((tab) => {
    tab.dataset.label = tab.textContent.trim();
});

document.querySelector('[role="tablist"]').addEventListener('keydown', tabToets);

// `input` en niet `change`: bij `change` gaat een getal dat je net intikte verloren zodra je de
// pagina herlaadt zonder eerst het veld te verlaten.
document.getElementById('paneel').addEventListener('input', (gebeurtenis) => {
    if (VELDEN.includes(gebeurtenis.target.id)) bewaarVelden();
});

// Het merkteken hoort bij elke actieknop; het hier aanhangen scheelt dezelfde span bij elke knop in
// de opmaak — en voorkomt dat één knop hem mist en als enige niets laat zien.
document.querySelectorAll('button[data-pad]').forEach((knop) => {
    const merk = document.createElement('span');

    merk.className = 'knop__uitkomst';
    knop.append(merk);
});

herstelStand();
pasOmgevingToe();
vulPersonas();
verversToestand();

// Alleen pollen terwijl er iemand kijkt: een demo-console blijft dagen in een tab openstaan.
setInterval(() => {
    if (!document.hidden) verversToestand();
}, POLL_MS);
