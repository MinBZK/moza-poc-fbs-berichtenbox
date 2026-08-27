/* Bediening van de demo-stack. Los van index.html zodat de opmaak leesbaar blijft en er geen
 * inline `onclick` meer nodig is: elke knop draagt zijn methode en pad als data-attribuut, en één
 * listener op het paneel voert ze uit. */

const BOX_PAD = '/moza/berichtenbox/';

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

/* Loopt er een actie, dan slaat de poll over: de statusbalk zou anders de toestand van halverwege
 * een herstel tonen, en de melding van "Bezig…" overschrijven. */
let bezig = false;

/* Onthouden zodra de omgeving stub-magazijnen blijkt te hebben. Daarna blijft de chip staan, ook
 * als de volgende uitlezing mislukt — anders verdwijnt bij een storing juist het teken dat er iets
 * mis is. */
let kentStubMagazijnen = false;

const melding = document.getElementById('melding');
const meldingTekst = document.getElementById('melding-tekst');
const meldingRuw = document.getElementById('melding-ruw');
const meldingJson = document.getElementById('melding-json');

// ---------------------------------------------------------------- waar je gebleven was

/* Storage kan gooien — een private window, of een browser die site-data blokkeert. Het paneel moet
 * dan gewoon werken, alleen zonder geheugen; vandaar dat lezen en schrijven allebei stil terugvallen. */
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

/* De keuzelijst met persona's komt pas binnen na een netwerkaanroep; die zet vulPersonas() zelf
 * terug, zodra hij weet welke opties er zijn. */
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

/* Het frame laadt alleen als deze pagina via de demo-proxy geopend is; alleen daar staat de
 * proeftuin op dezelfde origin. Een frame dat cross-origin niet laadt geeft geen foutmelding die
 * JavaScript kan zien, dus toetsen we het pad vooraf in plaats van achteraf te raden. */
function bepaalBox() {
    fetch(BOX_PAD, { method: 'HEAD' })
        .then((respons) => toonBox(respons.ok))
        .catch(() => toonBox(false));
}

function toonBox(bereikbaar) {
    const box = document.getElementById('box');

    box.hidden = !bereikbaar;
    document.getElementById('geen-box').hidden = bereikbaar;

    if (bereikbaar) box.src = BOX_PAD;
}

/* Zelfde origin, dus een gerichte herlaad kan zonder dat de proeftuin daar iets voor hoeft te
 * bouwen. Bewust een knop en niet automatisch na elke actie: een herlaad zet de berichtenbox terug
 * op zijn beginstand, en midden in een demo bepaal je zelf wanneer dat mag. */
function verversBox() {
    const box = document.getElementById('box');

    if (box.hidden) return;

    try {
        box.contentWindow.location.reload();
    } catch (fout) {
        box.src = BOX_PAD;
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
    meldingRuw.open = false;
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
        '. ' + vullingTekst(body.vulling),

    status: (body) => body.status,

    tempo: (body) =>
        body.loopt
            ? 'Stroom loopt: elke ' + body.intervalSeconden + ' s, ' + body.geleverd + ' geleverd'
            : 'Stroom staat uit',

    storingen: (body) => storingenTekst(body),

    'veel-magazijnen': (body) => body.actief + ' van ' + body.totaal + ' stub-magazijnen actief',

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

    if (!formatter) return null;

    try {
        return formatter(body);
    } catch (fout) {
        // Een antwoord in een andere vorm dan verwacht mag geen lege melding geven: de ruwe JSON
        // eronder is dan het enige wat er nog te zien valt.
        return null;
    }
}

// ---------------------------------------------------------------- acties uitvoeren

/* Drie gescheiden uitkomsten, want tijdens een demo moet één blik volstaan om te zien wát er stuk
 * is: de console niet bereikbaar, de actie geweigerd (met de melding uit de body), of gelukt.
 * Zonder de .ok-toets levert elke storing dezelfde onbruikbare 'SyntaxError' op, omdat het parsen
 * dan over een foutbody struikelt. */
async function roep(pad, methode) {
    let respons;

    try {
        respons = await fetch(pad, { method: methode });
    } catch (fout) {
        return { gelukt: false, tekst: 'Geen verbinding met de demo-console: ' + fout, ruw: null };
    }

    const tekst = await respons.text();

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

    const uitkomst = await roep(pad, knop.dataset.methode);

    knop.disabled = false;
    zetUitkomst(knop, uitkomst.gelukt ? 'gelukt' : 'mislukt');

    if (uitkomst.gelukt) {
        const tekst = samenvatting(knop.dataset.samenvatting, uitkomst.body);

        toonMelding(tekst || 'Gelukt', 'goed', uitkomst.ruw);
    } else {
        toonMelding(uitkomst.tekst, 'fout', uitkomst.ruw);
    }

    bezig = false;

    verversToestand();
}

// ---------------------------------------------------------------- bevestiging

/* In het paneel zelf en niet via confirm(): die dialoog valt buiten het scherm dat je deelt, en
 * dwingt bovendien tot één tekst voor knoppen die heel verschillende dingen doen. */
function sluitBevestiging() {
    document.querySelectorAll('.bevestig').forEach((blok) => blok.remove());
}

function vraagBevestiging(knop) {
    sluitBevestiging();

    const blok = document.createElement('div');
    const vraag = document.createElement('p');
    const knoppen = document.createElement('div');
    const ja = document.createElement('button');
    const nee = document.createElement('button');

    blok.className = 'bevestig';
    vraag.className = 'bevestig__vraag';
    vraag.textContent = knop.dataset.bevestig + ' Doorgaan?';
    knoppen.className = 'bevestig__knoppen';

    nee.type = 'button';
    nee.className = 'knop';
    nee.textContent = 'Nee, laat staan';
    nee.addEventListener('click', () => {
        sluitBevestiging();
        knop.focus();
    });

    ja.type = 'button';
    ja.className = 'knop knop--gevaar';
    ja.textContent = 'Ja, doorgaan';
    ja.addEventListener('click', () => {
        sluitBevestiging();
        voerUit(knop);
    });

    knoppen.append(nee, ja);
    blok.append(vraag, knoppen);
    knop.closest('.knoppen').insertBefore(blok, knop.nextSibling);

    // Focus op de veilige keuze: de gebruiker kwam hier met een klik of een Enter, en een tweede
    // aanslag mag geen twee magazijnen leegtrekken.
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

    sluitBevestiging();
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

function markeerTab(id, letOp) {
    document.getElementById(id).dataset.letOp = String(letOp);
}

// ---------------------------------------------------------------- toestandsbalk

async function lees(pad) {
    try {
        const respons = await fetch(pad);

        return respons.ok ? await respons.json() : null;
    } catch (fout) {
        return null;
    }
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
    if (!tempo) return zetChip('chip-stroom', 'onbekend', 'let-op');

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

    const afwijkend = Object.entries(storingen).filter(([, toestand]) => toestand !== 'normaal');

    if (!afwijkend.length) {
        zetChip('chip-storingen', 'geen', 'goed');
    } else {
        zetChip('chip-storingen', afwijkend.map(([proxy, toestand]) => naam(proxy) + ' ' + toestand).join(' · '), 'fout');
    }

    markeerTab('tab-storingen', afwijkend.length > 0);
}

function toonMagazijnen(veel) {
    const chip = document.getElementById('chip-magazijnen');

    if (veel && veel.totaal > 0) kentStubMagazijnen = true;

    if (!kentStubMagazijnen) return;

    chip.hidden = false;

    if (!veel) {
        zetChip('chip-magazijnen', 'onbekend', 'let-op');

        return;
    }

    const beperkt = veel.actief < veel.totaal;

    zetChip('chip-magazijnen', veel.actief + '/' + veel.totaal, beperkt ? 'let-op' : null);
    markeerTab('tab-scenarios', beperkt);
}

/* Vier losse uitlezingen, elk met een eigen terugval: een Toxiproxy die niet antwoordt mag de
 * berichtentelling niet meeslepen, want juist dan wil je weten hoeveel er nog staat. */
async function verversToestand() {
    if (bezig) return;

    const [status, tempo, storingen, veel] = await Promise.all([
        lees('/api/demo/status'),
        lees('/api/demo/tempo'),
        lees('/api/demo/storing'),
        lees('/api/demo/veel-magazijnen'),
    ]);

    toonBerichten(status);
    toonStroom(tempo);
    toonStoringen(storingen);
    toonMagazijnen(veel);
}

// ---------------------------------------------------------------- omgeving en persona's

/* Niet elke omgeving heeft elke proxy: op ZAD ontbreken de magazijn-storingen, omdat de magazijnen
 * hun gedrag daar uit de simulator krijgen. Een knop tonen die gegarandeerd een 400 geeft, kost
 * tijdens een demo uitleg die niets toevoegt. Console onbereikbaar: laat alles staan — die knoppen
 * falen dan zichtbaar, wat beter is dan een leeg tabblad zonder uitleg. */
async function pasOmgevingToe() {
    const omgeving = await lees('/api/demo/omgeving');

    if (!omgeving) return;

    const beschikbaar = new Set(omgeving.storingen);

    document.querySelectorAll('button[data-proxy]').forEach((knop) => {
        knop.hidden = !beschikbaar.has(knop.dataset.proxy);
    });

    document.querySelectorAll('.groep[data-groep]').forEach((groep) => {
        const knoppen = Array.from(groep.querySelectorAll('button[data-proxy]'));

        groep.hidden = knoppen.every((knop) => knop.hidden);
    });

    document.getElementById('geen-storingen').hidden = beschikbaar.size > 0;
}

/* De ontdubbeling loopt op een BSN, dus alleen persona's met een BSN kunnen hem spelen. Een vrij
 * tekstveld zou een BSN vragen die verderop in dezelfde pagina al als keuzelijst bestaat. */
async function vulPersonas() {
    const keuze = document.getElementById('ontdubbelPersona');
    const knop = document.querySelector('button[data-samenvatting="ontdubbeling"]');
    const personas = (await lees('/api/demo/personas')) || [];
    const metBsn = personas.filter((persona) => persona.ontvanger.startsWith('BSN:'));

    keuze.replaceChildren();

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

    // Een persona die er niet meer is — andere configuratie, ander stub-register — valt terug op
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

document.querySelector('[role="tablist"]').addEventListener('keydown', tabToets);

// `input` en niet `change`: bij `change` gaat een getal dat je net intikte verloren zodra je
// ververst zonder eerst het veld te verlaten.
document.getElementById('paneel').addEventListener('input', (gebeurtenis) => {
    if (VELDEN.includes(gebeurtenis.target.id)) bewaarVelden();
});

// Het merkteken hoort bij elke actieknop; het hier aanhangen scheelt dezelfde span twintig keer in
// de opmaak — en voorkomt dat één knop hem mist en als enige niets laat zien.
document.querySelectorAll('button[data-pad]').forEach((knop) => {
    const merk = document.createElement('span');

    merk.className = 'knop__uitkomst';
    knop.append(merk);
});

herstelStand();
bepaalBox();
pasOmgevingToe();
vulPersonas();
verversToestand();

// Alleen pollen terwijl er iemand kijkt: een demo-console blijft dagen in een tab openstaan.
setInterval(() => {
    if (!document.hidden) verversToestand();
}, POLL_MS);
