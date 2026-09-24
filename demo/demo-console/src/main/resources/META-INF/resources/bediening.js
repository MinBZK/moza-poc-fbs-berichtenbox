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

const VELDEN = ['aantal', 'tempoInterval', 'actiefAantal', 'ontdubbelPersona', 'berichtPersona', 'berichtAantal', 'berichtWillekeurigTijdstip'];
const POLL_MS = 5000;
const UITKOMST_MS = 4000;

/* Korter dan POLL_MS, zodat hangende uitlezingen niet op elkaar stapelen. De toestandsbalk zou ook
 * zonder timeout niet verouderen — die kent een beurt-guard — maar het inrichten van de omgeving
 * kent die niet en blijft zonder deze grens onbeperkt hangen. */
const LEES_TIMEOUT_MS = 4000;

/* Wachttijden tussen twee pogingen om de omgeving te lezen; de laatste geldt voor alles daarna. */
const INRICHT_WACHT = [2000, 5000, 15000, 30000];

/* De lijst van gesimuleerde magazijnen is het zwaarste antwoord en verandert alleen door een actie;
 * die hoeft niet in het ritme van de toestandsbalk mee. */
const SIMULATOR_INFO_MS = 30000;

/* Hoe vaak het Info-blad zijn tijdlabels en de planning van de simulatorlijst naloopt. */
const TIK_MS = 1000;

/* De laatste stand van elk blok op het Info-blad, zodat een refresh meteen iets toont in plaats van
 * een leeg blad tot de eerste uitlezing terug is. Een eigen sleutel naast de stand: deze wordt elke
 * vijf seconden herschreven, de stand alleen bij een klik. */
const INFO_SLEUTEL = 'fbs-demo-bediening:info';

/* Sleutels zoals de API ze gebruikt, namen zoals ze in de demo genoemd worden. Alleen hier: de
 * knoppen en de statusbalk mogen niet ieder hun eigen vertaling verzinnen. */
/* Zoals de lijsten in de demo heten; `watOpenstaat` zet ze in een melding, en daar hoort geen
 * API-veldnaam te staan. */
const LIJSTNAMEN = {
    personas: 'de keuzelijst voor de ontdubbeling',
    berichtPersonas: 'de keuzelijst voor een bericht aan één persona',
};

const MAGAZIJN_NAMEN = {
    'magazijn-a': 'RVO',
    'magazijn-b': 'Bel.dienst',
};

/* Voluit, voor het Info-blad: daar is de ruimte die een chip niet heeft. Dezelfde namen als op de
 * knoppen van het tabblad Storingen, zodat een blok en een knop hetzelfde onderdeel ook zo noemen. */
const ONDERDEEL_NAMEN = {
    'magazijn-a': 'Magazijn A (RVO)',
    'magazijn-b': 'Magazijn B (Belastingdienst)',
    uitvraag: 'Uitvraag',
    aanmeld: 'Uitvraag/aanmeld',
    personadienst: 'Personadienst',
    simulator: 'Magazijn-simulator',
    profiel: 'Profielservice',
    notificatie: 'Notificatie',
    redis: 'Redis',
};

/* Hoeveel acties er lopen; zolang dat er meer dan nul zijn slaat de poll over, want de statusbalk
 * zou anders de toestand van halverwege een herstel tonen. Een teller en geen vlag: de ingedrukte
 * knop gaat op disabled maar een ándere niet, dus twee acties kunnen overlappen — en dan zette de
 * eerste die terugkwam de vlag voor allebei terug. */
let bezig = 0;

/* Volgnummer per ververs-ronde. Een poll die al onderweg was toen je klikte, mag de verse toestand
 * van ná die actie niet overschrijven. */
let ververslus = 0;

/* Of deze omgeving gesimuleerde magazijnen kent, uit /api/demo/omgeving; null zolang dat nog niet
 * gelezen is. Uit de configuratie en niet uit een geslaagde uitlezing: anders is "niet ingericht"
 * niet te onderscheiden van "niet kunnen lezen", en verdwijnt de chip juist wanneer er iets stuk
 * is. */
let heeftSimulator = null;

/* Per blok op het Info-blad: de laatst gelezen inhoud, wanneer die binnenkwam, en of de poging daarna
 * mislukte. */
const infoStand = {};

/* De simulatorlijst loopt niet mee in de ronde van de toestandsbalk en heeft dus een eigen
 * volgnummer. `simulatorVerlopen` zet een actie aan: die kan het gedrag net veranderd hebben. */
let simulatorBeurt = 0;
let laatsteSimulatorPoging = 0;
let simulatorVerlopen = false;

/* Hoeveel automatische pogingen om de omgeving te lezen er al mislukt zijn, en de timer van de
 * volgende. Nul zodra het gelukt is: een omgeving die later opnieuw wegvalt begint weer met de korte
 * wachttijd. `inrichtLoopt` sluit een tweede poging naast een lopende uit — die twee zouden elkaars
 * uitkomst overschrijven, en de laatste die terugkomt hoeft niet de meest actuele te zijn. */
let inrichtPoging = 0;
let inrichtTimer = null;
let inrichtLoopt = false;

const melding = document.getElementById('melding');
const meldingTekst = document.getElementById('melding-tekst');
const meldingLetOp = document.getElementById('melding-letop');
const meldingRuw = document.getElementById('melding-ruw');
const meldingJson = document.getElementById('melding-json');
const inrichting = document.getElementById('inrichting');
const inrichtingTekst = document.getElementById('inrichting-tekst');
const inrichtingKnop = document.getElementById('inrichting-knop');

/* Eén vangnet voor wat buiten een eigen try/catch omvalt: een throw uit de click-listener, en een
 * afgewezen promise uit de aanroepen die fire-and-forget zijn. Zonder dit blijft zo'n fout in de
 * browserconsole hangen — en wie een demo geeft heeft geen devtools open, dus die ziet alleen een
 * knop die niets doet.
 *
 * Meteen hier en niet onderaan bij de rest van de bedrading: die bedrading zoekt zelf elementen op
 * en kan dus zélf omvallen, en dan was het vangnet nog niet geregistreerd. Alleen de lookups
 * hierboven vallen erbuiten, en die gooien niet. */
window.addEventListener('error', (gebeurtenis) => {
    meldVangnet(gebeurtenis.message, gebeurtenis.error);
});

window.addEventListener('unhandledrejection', (gebeurtenis) => {
    const reden = gebeurtenis.reason;

    meldVangnet(reden && reden.message ? reden.message : String(reden), reden);
});

/* Welke vangnet-fout op dit moment in de meldingsbalk staat. De poll draait elke vijf seconden, dus
 * een storing in die lus levert steeds dezelfde fout op; zonder deze toets schrijft die elke ronde
 * over de uitkomst van de laatste actie heen. `toonMelding` wist hem, dus zodra iets anders de balk
 * overschrijft, mag dezelfde fout opnieuw gemeld worden — anders zwijgt de tweede druk op een
 * kapotte knop. */
let laatsteVangnetfout = null;

function meldVangnet(boodschap, oorzaak) {
    // Het originele object erbij: de afgevlakte boodschap draagt geen stack.
    console.error('[bediening] onverwachte fout', oorzaak || boodschap);

    if (boodschap === laatsteVangnetfout) return;

    toonMelding('Onverwachte fout in het paneel: ' + boodschap, 'fout', null);

    // Ná `toonMelding`, want die wist de vlag juist.
    laatsteVangnetfout = boodschap;
}

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

        // Een vakje draagt zijn stand in `checked`; `value` is er altijd 'on' en zou het
        // uitgevinkte geval als aangevinkt terugzetten.
        if (veld) velden[id] = veld.type === 'checkbox' ? veld.checked : veld.value;
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

        if (!veld || veld.tagName === 'SELECT') return;

        if (veld.type === 'checkbox') {
            // Expliciet op booleaan toetsen: `false` is een geldige bewaarde stand, en een
            // waarheidstoets zou die als "niets bewaard" lezen en het vakje laten staan.
            if (typeof velden[id] === 'boolean') veld.checked = velden[id];

            return;
        }

        if (velden[id]) veld.value = velden[id];
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

    if (!box) {
        meldOpmaakfout('het frame van de berichtenbox');

        return;
    }

    // De knop staat er ook wanneer het frame niet in beeld is; zonder deze regel doet hij dan niets
    // en zegt niets waarom.
    if (box.hidden) {
        toonMelding('De berichtenbox staat niet in beeld; er valt niets te verversen', 'let-op', null);

        return;
    }

    try {
        box.contentWindow.location.reload();
        toonMelding('Berichtenbox herladen', 'goed', null);
    } catch (fout) {
        // Verwacht bij een frame op een andere origin; maar een frame dat nog niet geladen is geeft
        // hier dezelfde tak, en dan zegt alleen het log welk van de twee het was.
        console.error('[bediening] frame niet direct te herladen, terugval op src', fout);

        box.src = boxUrl;
        toonMelding('Berichtenbox opnieuw geladen op ' + boxUrl, 'goed', null);
    }
}

/* De richting van het teken zegt wat er gebeurt — » duwt de bediening weg, « haalt hem terug. */
function klap() {
    const ingeklapt = document.body.classList.toggle('ingeklapt');

    werkKlapBij();
    bewaarStand({ ingeklapt: ingeklapt });
}

/* Het teken, het bijschrift en het merkteken van de klap-knop blijven hier bij elkaar, zodat een in-
 * of uitklap het merkteken niet wist. */
function werkKlapBij() {
    const knop = document.getElementById('klap');

    if (!knop) return;

    const ingeklapt = document.body.classList.contains('ingeklapt');
    const letOp = knop.dataset.letOp === 'true';
    const bijschrift = (ingeklapt ? 'Bediening tonen' : 'Bediening verbergen') +
        (letOp ? ' — het paneel is niet volledig ingericht' : '');

    knop.textContent = ingeklapt ? '«' : '»';
    knop.setAttribute('aria-expanded', String(!ingeklapt));
    knop.setAttribute('aria-label', bijschrift);
    knop.title = bijschrift;
}

/* Het blok over een mislukte inrichting staat ín het paneel, en een ingeklapt paneel is
 * `display: none`. De klap-knop staat ernaast en blijft wél staan; zonder dit merkteken erop is een
 * half ingericht paneel achter een ingeklapte bediening nergens aan te zien. */
function markeerKlap(letOp) {
    const knop = document.getElementById('klap');

    if (!knop) return;

    knop.dataset.letOp = String(letOp);

    werkKlapBij();
}

// ---------------------------------------------------------------- melding

/* Sommige antwoorden dragen uitleg die los staat van de uitkomst — waarom de Berichtenbox na een
 * reset nog even doet alsof er niets veranderd is, bijvoorbeeld. Op een eigen regel en niet in de
 * samenvatting: die moet in één oogopslag te lezen blijven. */
function letOp(body) {
    return body && typeof body.letOp === 'string' ? body.letOp : null;
}

function toonMelding(tekst, soort, ruw, uitleg) {
    // Draagt de opmaak de meldingsbalk niet compleet, dan is de console het laatste kanaal dat
    // overblijft. Hier gooien zou elke aanroeper meeslepen — ook het vangnet dat juist fouten toont,
    // en dat zou zijn eigen throw opnieuw binnenkrijgen.
    if (!melding || !meldingTekst || !meldingLetOp || !meldingRuw || !meldingJson) {
        console.error('[bediening] melding niet te tonen:', tekst);

        // Niet via `registreerPaneelfout`: die meldt, en melden is precies wat hier niet kan. De stip
        // op de klap-knop hangt aan een ander element en werkt wél.
        openstaandePaneelfouten.add('de meldingsbalk');
        markeerKlap(true);

        return;
    }

    melding.hidden = false;
    melding.className = 'melding' + (soort ? ' melding--' + soort : '');
    meldingTekst.textContent = tekst;
    meldingLetOp.hidden = !uitleg;
    meldingLetOp.textContent = uitleg || '';
    meldingRuw.hidden = !ruw;

    // Alles wat niet goed ging staat meteen open: dan is de ruwe JSON het aanknopingspunt, en
    // tijdens een demo klapt niemand een <details> uit.
    meldingRuw.open = Boolean(ruw) && soort !== 'goed';
    meldingJson.textContent = ruw || '';

    // Wat het vangnet toonde staat er nu niet meer; dezelfde fout mag daarna opnieuw gemeld worden.
    laatsteVangnetfout = null;
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
    vulling: (body) => vullingTekst(body),

    /* Apart van `berichten`, dat "wat er nu staat" toont: hier gaat het om wat er wég is. Dezelfde
     * formatter voor beide las na het legen als een magazijn dat nog vol stond. */
    legen: (body) =>
        'Geleegd: ' +
        Object.entries(body.magazijnen).map(([sleutel, aantal]) => naam(sleutel) + ' ' + aantal).join(', ') +
        (body.gesimuleerd.overgeslagen
            ? '. Gesimuleerde magazijnen overgeslagen: ' + body.gesimuleerd.overgeslagen
            : '. Gesimuleerd: ' + body.gesimuleerd.berichten + ' berichten uit ' +
              body.gesimuleerd.magazijnen + ' magazijnen'),

    herstel: (body) =>
        'Hersteld. Geleegd: ' +
        Object.entries(body.geleegd).map(([sleutel, aantal]) => naam(sleutel) + ' ' + aantal).join(', ') +
        '. ' + vullingTekst(body.vulling) +
        // De echte magazijnen zijn dan wél hersteld; wie dat niet leest gaat de knop opnieuw
        // indrukken of zoeken naar een fout die er niet is.
        (body.gesimuleerd.overgeslagen
            ? '. Gesimuleerde magazijnen overgeslagen: ' + body.gesimuleerd.overgeslagen
            : '. Gesimuleerd: ' + body.gesimuleerd.berichten + ' weg, ' + body.gesimuleerdGevuld + ' klaargezet'),

    status: (body) => body.status,

    tempo: (body) =>
        body.loopt
            ? 'Stroom loopt: elke ' + body.intervalSeconden + ' s, ' + body.geleverd + ' geleverd'
            : 'Stroom staat uit',

    simulator: (body) => body.actief + ' van ' + body.totaal + ' gesimuleerde magazijnen zonder storing',

    'simulator-vullen': (body) =>
        body.berichten + ' berichten en ' + body.bijlagen + ' bijlagen klaargezet in ' +
        body.magazijnen + ' gesimuleerde magazijnen' +
        (body.overgeslagen ? ', ' + body.overgeslagen + ' stonden er al' : ''),

    'simulator-legen': (body) =>
        body.berichten + ' berichten weg; ' + body.magazijnen + ' gesimuleerde magazijnen terug op hun gedrag',

    sessie: (body) => body.gewisteKeys + ' sessie-key(s) gewist; de volgende uitvraag geeft 409',

    'foutieve-aanlevering': (body) => 'Het magazijn wees de aanlevering af met HTTP ' + body.status,

    ontdubbeling: (body) =>
        'Event ' + body.eventId + ' tweemaal aangeboden: HTTP ' + body.eersteStatus +
        ' en HTTP ' + body.tweedeStatus,
};

/* De uitkomstsoorten van een samenvatting vertaald naar het merkteken naast de knop. Zonder
 * 'fout' erin viel een volledig mislukte vulling in de 'gelukt'-tak. Een soort die hier ontbreekt
 * valt daarom op 'let-op' terug en niet op 'gelukt': een uitkomst die we niet kennen is twijfel. */
const MERKTEKEN = { 'fout': 'mislukt', 'let-op': 'let-op', 'goed': 'gelukt' };

function vullingTekst(vulling) {
    let tekst = vulling.geslaagd + ' van ' + vulling.aangeboden + ' berichten aangeleverd';

    if (vulling.mislukt) tekst += ', ' + vulling.mislukt + ' mislukt';

    if (vulling.markeringMislukt) tekst += ', ' + vulling.markeringMislukt + ' niet op gelezen gezet';

    /* Het bericht staat in het magazijn, maar het magazijn bevestigde het zonder berichtnummer.
     * Zonder deze regel leest zo'n ronde als volledig geslaagd terwijl het magazijn haperde. */
    if (vulling.zonderBerichtId) {
        tekst += ', ' + vulling.zonderBerichtId + ' zonder bevestigd berichtnummer';
    }

    return tekst;
}

function samenvatting(soort, body) {
    const formatter = SAMENVATTINGEN[soort];

    // Een knop zonder bekende samenvatting is een bedradingsfout; groen "Gelukt" laat die eruitzien
    // als een geslaagde actie waarvan het antwoord begrepen is.
    if (!formatter) return { tekst: 'Geslaagd, maar het paneel kent de samenvatting van deze knop niet', soort: 'let-op' };

    try {
        const tekst = formatter(body);

        // Een formatter die niets oplevert, begreep het antwoord evenmin.
        if (!tekst) return { tekst: 'Geslaagd, maar het antwoord had een onverwachte vorm — zie de JSON hieronder', soort: 'let-op' };

        return { tekst: tekst, soort: vullingSoort(body) };
    } catch (fout) {
        // Een antwoord in een andere vorm dan verwacht is zelf een signaal: als gewoon "Gelukt"
        // tonen laat een keten die iets anders teruggeeft er gezond uitzien.
        return { tekst: 'Geslaagd, maar het antwoord had een onverwachte vorm — zie de JSON hieronder', soort: 'let-op' };
    }
}

/* HTTP 200 zegt alleen dat de console het verzoek verwerkte, niet dat de berichten aankwamen. Een
 * groene melding boven "100 mislukt" is het verkeerde signaal. */
function vullingSoort(body) {
    // Een overgeslagen stap of sessies die bleven staan, zijn geen fout — het echte werk is gelukt —
    // maar horen ook niet groen te zijn. Ze maken de uitkomst hooguit strenger: een vulling die
    // helemaal mislukte, blijft rood.
    const bijzaakMislukt = Boolean(body && ((body.gesimuleerd && body.gesimuleerd.overgeslagen) || body.sessiesNietGewist));

    const vulling = body && body.vulling ? body.vulling : body;

    if (!vulling || typeof vulling.aangeboden !== 'number') return bijzaakMislukt ? 'let-op' : 'goed';

    // Nul aangeboden is geen fout — er ging niets mis — maar groen zou hier "gelukt" betekenen voor
    // een actie die niets deed. `/random` en `/bericht` weigeren een aantal van nul zelf; wat hier
    // overblijft zijn de basisvulling en het herstel, die nul aanbieden zodra `dataset/basis.json`
    // leeg is. De simulator-vulling loopt hier níet doorheen: die antwoordt zonder `aangeboden`.
    if (vulling.aangeboden === 0) return 'let-op';

    if (vulling.geslaagd === 0) return 'fout';

    return bijzaakMislukt || vulling.mislukt || vulling.markeringMislukt || vulling.zonderBerichtId ? 'let-op' : 'goed';
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

    // Vóór het ontleden: de muur antwoordt met een inlogpagina in HTML, en die levert hieronder een
    // "onleesbaar antwoord" op — een melding die de bediener naar de keten laat zoeken terwijl er
    // alleen opnieuw ingelogd moet worden.
    if (!respons.ok && await inlogsessieVerlopen(respons.status)) {
        return { gelukt: false, tekst: muurMelding(), ruw: null };
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

/* De naam waaronder een veld in een melding staat. Uit een attribuut en niet uit het `<label>`
 * ernaast: dat label leest als losse woorden om het veld heen ("Elke … seconden") en twee groepen
 * dragen allebei een veld dat "Aantal" heet, dus de melding zou niet zeggen wélk aantal. */
function veldnaam(veld) {
    return veld.dataset.veldnaam || veld.id;
}

function opsom(namen) {
    if (!namen.length) return '';

    return namen.length < 2 ? namen[0] : namen.slice(0, -1).join(', ') + ' en ' + namen[namen.length - 1];
}

function metHoofdletter(tekst) {
    return tekst.charAt(0).toUpperCase() + tekst.slice(1);
}

/* Een `{veldnaam}` in het pad komt uit het invoerveld met die id. Ongeldige invoer wordt hier
 * gestopt en niet bij de server.
 *
 * Drie uitkomsten en niet één, want ze hebben drie verschillende oorzaken en dus drie antwoorden:
 * een veld dat de opmaak niet meer draagt is een kapot paneel, een leeg veld vraagt om invoer, en
 * een gevuld-maar-ongeldig veld vraagt om andere invoer. Eén gedeelde uitkomst laat `voerUit`
 * terugkeren zonder melding of merkteken — de knop doet dan niets en niets zegt waarom. De
 * keuzelijsten dragen bovendien geen `required`, dus daarvoor zwijgt `reportValidity()` en is deze
 * melding het enige dat de bediener ziet. */
function vulPadIn(pad) {
    const kwijt = [];
    const leeg = [];
    const ongeldig = [];

    // Alleen bij het eerste struikelende veld: de browser toont er toch maar één, en dan liever de
    // eerste in het pad dan de laatste die we tegenkwamen.
    let aanwijzen = null;

    const ingevuld = pad.replace(/\{(\w+)\}/g, (heel, id) => {
        const veld = document.getElementById(id);

        // Ook een element dat geen formulierveld is: `checkValidity()` bestaat daar niet, en die
        // TypeError zou als generieke "onverwachte fout" in het vangnet belanden. Deze uitgang
        // noemt het veld bij naam.
        if (!veld || typeof veld.checkValidity !== 'function') {
            kwijt.push(id);

            return '';
        }

        // Een vakje is nooit leeg en nooit ongeldig; zijn `value` is 'on' ongeacht de stand.
        if (veld.type === 'checkbox') return veld.checked ? 'true' : 'false';

        if (veld.value === '' || !veld.checkValidity()) {
            (veld.value === '' ? leeg : ongeldig).push(veldnaam(veld));
            aanwijzen = aanwijzen || veld;

            return '';
        }

        return encodeURIComponent(veld.value);
    });

    if (aanwijzen) aanwijzen.reportValidity();

    if (kwijt.length) return { opmaakfout: (kwijt.length > 1 ? 'de velden ' : 'het veld ') + opsom(kwijt) };

    // Allebei de oorzaken in één melding: een knop met een leeg én een ongeldig veld zou anders na
    // het invullen van het ene een tweede, andere afwijzing geven.
    const redenen = [];

    if (leeg.length) redenen.push('vul eerst ' + opsom(leeg) + ' in');

    if (ongeldig.length) redenen.push(opsom(ongeldig) + (ongeldig.length > 1 ? ' zijn' : ' is') + ' niet geldig');

    if (redenen.length) return { fout: metHoofdletter(redenen.join('; ')) };

    return { pad: ingevuld };
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
    const invoer = vulPadIn(knop.dataset.pad);

    // Een knop die niet kán, zegt dat langs beide kanalen: de melding zegt wát er mist, het
    // merkteken zegt wélke knop het was. Zonder dat allebei blijft een druk op de knop tijdens een
    // demo een storing zonder aanknopingspunt.
    if (invoer.opmaakfout) {
        zetUitkomst(knop, 'mislukt');
        meldOpmaakfout(invoer.opmaakfout);

        return;
    }

    if (invoer.fout) {
        zetUitkomst(knop, 'mislukt');
        toonMelding(invoer.fout, 'fout', null);

        return;
    }

    const pad = invoer.pad;

    bezig += 1;
    zetActieLoopt(knop, true);
    zetUitkomst(knop, 'bezig');
    toonMelding('Bezig…', null, null);

    // Alles opruimen in een finally: een teller die blijft hangen zet de poll stil, en dan toont de
    // balk de rest van de sessie verouderde waarden zonder dat iets dat verraadt.
    try {
        const uitkomst = await roep(pad, knop.dataset.methode);

        if (uitkomst.gelukt) {
            const samengevat = samenvatting(knop.dataset.samenvatting, uitkomst.body);

            // Het merkteken pas hierna, en naar de soort van de samenvatting. HTTP 200 alleen zegt
            // te weinig: de console verwerkte het verzoek, maar dat zegt niets over de berichten.
            // Zonder deze vertaling stond er een groen vinkje naast "0 van 100 aangeleverd".
            zetUitkomst(knop, MERKTEKEN[samengevat.soort] || 'let-op');
            toonMelding(samengevat.tekst, samengevat.soort, uitkomst.ruw, letOp(uitkomst.body));
        } else {
            zetUitkomst(knop, 'mislukt');
            toonMelding(uitkomst.tekst, 'fout', uitkomst.ruw);
        }
    } catch (fout) {
        // Ook naar de console: de melding vlakt de fout af tot een regel tekst, en juist hier is de
        // stack het enige dat verder helpt.
        console.error('[bediening] actie mislukt', pad, fout);
        zetUitkomst(knop, 'mislukt');
        toonMelding('Onverwachte fout in het paneel: ' + fout, 'fout', null);
    } finally {
        zetActieLoopt(knop, false);

        // Een knop die tijdens de actie op disabled ging, verliest de focus naar <body>. Alleen
        // teruggeven als hij daar nog staat, zodat we hem niet weghalen bij wie intussen verder
        // getabd is — en alleen als hij ook echt weer indrukbaar is, want focus op een uitgezette
        // knop laat de toetsenbordnavigatie stranden op iets waar niets meer gebeurt.
        if (document.activeElement === document.body && !knop.disabled) knop.focus();

        bezig -= 1;

        simulatorVerlopen = true;

        verversToestand();
    }
}

/* Twee onafhankelijke redenen kunnen een knop uitzetten, met elk hun eigen eigenaar: er loopt een
 * actie vanaf deze knop (`voerUit`), of de keuzelijst waar hij aan hangt is niet bruikbaar
 * (`vulKeuze`). Ze schrijven daarom een eigen vlag en niet rechtstreeks `disabled`: schrijven ze dat
 * allebei, dan geeft het inrichten — dat zichzelf herhaalt — een knop midden in een lopende
 * aanlevering vrij, en levert een tweede druk hetzelfde bericht nog een keer aan. */
function werkKnopBij(knop) {
    knop.disabled = knop.dataset.actieLoopt === 'ja' || knop.dataset.wachtOpLijst === 'ja';
}

function zetActieLoopt(knop, loopt) {
    knop.dataset.actieLoopt = loopt ? 'ja' : 'nee';
    werkKnopBij(knop);
}

function zetWachtOpLijst(knop, wacht) {
    knop.dataset.wachtOpLijst = wacht ? 'ja' : 'nee';
    werkKnopBij(knop);
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

    // Meteen en niet pas bij de volgende tik: een simulatorlijst die na een actie verlopen is, begint
    // dan nu, en de tijdlabels tonen geen seconde een oude stand.
    if (gekozen.id === 'tab-info') tikInfo();
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

    if (!tab) {
        meldOpmaakfout('het tabblad ' + id, true);

        return;
    }

    tab.dataset.letOp = String(letOp);

    if (letOp) tab.setAttribute('aria-label', tab.dataset.label + ' — er staat iets aan');
    else tab.removeAttribute('aria-label');
}

// ---------------------------------------------------------------- toestandsbalk

/* De chips maken van null een zichtbare "onbekend", maar de reden staat alleen in het antwoord van
 * de console — en die draagt de exacte oorzaak in zijn body. Zonder deze regel heeft een bediener
 * die "onbekend" ziet geen enkel aanknopingspunt. */
async function lees(pad) {
    // Zonder deze timer wacht een uitlezing zolang de browser wil: een omgeving die de verbinding
    // openhoudt zonder te antwoorden — een trage cluster, een inlogpagina die ertussen komt — laat
    // alles wat op dit antwoord wacht onbeperkt hangen. De timeout maakt daar een mislukking van,
    // en een mislukking is zichtbaar en opnieuw te proberen.
    const staak = new AbortController();
    const timer = setTimeout(() => staak.abort(), LEES_TIMEOUT_MS);

    // Buiten de try: breekt de timer af tijdens het lezen van de body, dan is de status het enige
    // dat de oorzaak nog aanwijst — een 401 van een inlogpagina ertussen, bijvoorbeeld.
    let status = null;

    try {
        const respons = await fetch(pad, { signal: staak.signal });

        status = respons.status;

        if (respons.ok) return await respons.json();

        // De poll is het eerste dat een verlopen sessie tegenkomt, want die draait zodra de
        // bediener weer naar het tabblad kijkt. Hier herstellen betekent dus dat het paneel zichzelf
        // terugbrengt vóór de eerste druk op een knop.
        if (await inlogsessieVerlopen(status)) {
            toonMelding(muurMelding(), 'let-op', null);

            return null;
        }

        console.error('toestand niet te lezen:', pad, status, await respons.text());
    } catch (fout) {
        // Op de fout zelf en niet op `signal.aborted`: dat signal blijft afgebroken staan zodra de
        // timer is afgegaan, terwijl de fout zegt wat er werkelijk misging.
        const afgebroken = fout && fout.name === 'AbortError';

        console.error('toestand niet te lezen:', pad, status, afgebroken ? 'geen antwoord binnen ' + LEES_TIMEOUT_MS + ' ms' : fout);
    } finally {
        clearTimeout(timer);
    }

    return null;
}

function zetChip(id, tekst, soort) {
    const chip = document.getElementById(id);

    if (!chip) {
        meldOpmaakfout('de chip ' + id, true);

        return;
    }

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

/* Of de componenten zelf antwoorden. Los van de storingen-chip: die zegt wat Toxiproxy op de lijn
 * aanzet, deze wat er aan de overkant draait. Op een gedeelde omgeving hebben de magazijnen geen
 * proxy, en is dit de enige plek waar een plat magazijn zichtbaar wordt. */
function toonBereikbaarheid(bereikbaarheid) {
    if (!bereikbaarheid) return zetChip('chip-bereikbaarheid', 'onbekend', 'let-op');

    const componenten = Object.entries(bereikbaarheid);

    // Nul componenten is iets anders dan alles bereikbaar: er wordt dan niets bewaakt, en groen is
    // daar een geruststelling die nergens op slaat.
    if (!componenten.length) return zetChip('chip-bereikbaarheid', 'niet ingericht', null);

    const afwijkend = componenten.filter(([, toestand]) => toestand !== 'bereikbaar');

    if (!afwijkend.length) return zetChip('chip-bereikbaarheid', 'alle bereikbaar', 'goed');

    zetChip(
        'chip-bereikbaarheid',
        afwijkend.map(([component, toestand]) => naam(component) + ' ' + toestand.replace('-', ' ')).join(' · '),
        'fout',
    );
}

/* Elke uitlezing valt apart terug: een endpoint dat niet antwoordt maakt alleen zijn eigen chip
 * onbekend, want juist bij een storing wil je de overige tellingen nog zien. */
async function verversToestand(metHand) {
    // Zolang er een actie loopt toont de balk een halve toestand; dat is de reden van deze guard. Wie
    // er zelf om vroeg, hoort wél te horen waarom er niets verandert.
    if (bezig > 0) {
        if (metHand) toonMelding('Er loopt nog een actie; de toestand komt daarna vanzelf bij', null, null);

        return;
    }

    const beurt = ++ververslus;

    const [status, tempo, storingen, bereikbaarheid, veel] = await Promise.all([
        lees('/api/demo/status'),
        lees('/api/demo/tempo'),
        lees('/api/demo/storing'),
        lees('/api/demo/bereikbaarheid'),
        // Niet vragen naar wat deze omgeving niet heeft: dat levert elke vijf seconden een fout in
        // het log op, zonder dat er iets te tonen valt.
        heeftSimulator === false ? null : lees('/api/demo/simulator'),
    ]);

    // Een ronde die al liep toen er geklikt werd, mag de verse toestand van ná die actie niet
    // terugdraaien. Wie zelf op de knop drukte hoort dat wél te horen: anders duurt zijn uitlezing
    // tot de timeout en verandert er daarna niets zichtbaars.
    if (beurt !== ververslus) {
        if (metHand) toonMelding('Een nieuwere bijwerking nam het over', 'let-op', null);

        return;
    }

    toonBerichten(status);
    toonStroom(tempo);
    toonStoringen(storingen);
    toonBereikbaarheid(bereikbaarheid);
    toonMagazijnen(veel);

    werkInfoBij('berichten', status);
    werkInfoBij('stroom', tempo);
    werkInfoBij('storingen', storingen);
    werkInfoBij('componenten', bereikbaarheid);

    if (!metHand) return;

    // Anders is een druk op de knop alleen te zien wanneer er toevallig iets veranderde — en groen
    // terwijl elke chip op "onbekend" staat is het verkeerde signaal.
    const onleesbaar = [status, tempo, storingen, bereikbaarheid].filter((antwoord) => antwoord === null).length;

    if (onleesbaar === 0) toonMelding('Toestand bijgewerkt', 'goed', null);
    else toonMelding('Toestand bijgewerkt, maar ' + onleesbaar + ' uitlezing(en) kwamen niet door', 'let-op', null);
}

// ---------------------------------------------------------------- omgeving en persona's

/* Een proxy die deze omgeving niet aanbiedt krijgt geen knop: een knop die gegarandeerd een 400
 * geeft kost tijdens een demo uitleg die niets toevoegt. Console onbereikbaar: laat alles staan —
 * knoppen die zichtbaar falen zijn beter dan een leeg tabblad zonder uitleg. */
async function pasOmgevingToe() {
    const omgeving = await lees('/api/demo/omgeving');
    const gelezen = omgeving !== null;

    // Vooraan, want alles hieronder kan gooien: wat de vorige ronde miste mag een volgende ronde niet
    // blijven achtervolgen.
    onbruikbareLijsten.clear();

    heeftSimulator = omgeving ? omgeving.simulator : true;

    werkInfoBij('personas', omgeving);

    // Het adres van de berichtenbox komt uit ditzelfde antwoord. Is de console onbereikbaar, dan
    // blijft het eigen pad over — lokaal is dat het juiste adres.
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
        // oplevert kost tijdens een demo uitleg die niets toevoegt. Los daarvan het simulatorblok op
        // het info-blad: dat staat buiten die groep, dus het hangt aan zijn eigen markering.
        document.getElementById('groep-simulator').hidden = omgeving.simulator === false;

        document.querySelectorAll('[data-simulator]').forEach((element) => {
            element.hidden = omgeving.simulator === false;
        });
    }

    // Als laatste, en apart per lijst: elke keuzelijst hangt aan de vorm van het antwoord, en een
    // antwoord dat die vorm mist mag de knoppen hierboven niet meeslepen. Uit ditzelfde antwoord en niet van
    // /api/demo/personas: dat adres hoort bij de personadienst, en deze module beantwoordt het
    // bewust niet. Console onbereikbaar levert null op, waarop de keuzelijst zegt dat ze niet te
    // lezen was in plaats van dat er niets is ingericht.
    try {
        vulPersonas(omgeving ? omgeving.personas : null, gelezen);
    } catch (fout) {
        console.error('[bediening] persona-keuzelijst niet te vullen', fout);

        // Mét `gelezen`: een antwoord dat binnenkwam en waarop deze lijst stukloopt, is een dode
        // bediening — anders zou de volgende geslaagde poging het paneel compleet noemen.
        vulPersonas(null, gelezen);
    }

    try {
        vulBerichtPersonas(omgeving ? omgeving.berichtPersonas : null, gelezen);
    } catch (fout) {
        console.error('[bediening] keuzelijst voor losse berichten niet te vullen', fout);
        vulBerichtPersonas(null, gelezen);
    }

    // Pas nu weet de balk of de magazijnen-chip bestaat; zonder deze ronde blijft hij tot de
    // volgende poll leeg.
    verversToestand();

    return omgeving !== null;
}

/* Het inrichten opnieuw proberen, vanzelf én met een knop. Vanzelf, want een omgeving die even
 * wegvalt komt meestal terug en wie een demo geeft kijkt niet naar een knop; met een knop, want
 * wachten op de volgende poging is tijdens een demo geen optie en een refresh is de enige andere
 * uitweg uit een mislukte start.
 *
 * `metHand` scheidt die twee. Een druk op de knop hoort meteen antwoord te geven en telt niet mee
 * in de wachttijd — die loopt op omdat een console die weg is meestal een tijdje weg blijft, en elke
 * automatische poging kost een reeks mislukte uitlezingen in het log. */
async function richtIn(metHand) {
    // De automatische keten mag hier niet doodlopen: die plant zichzelf opnieuw, zodat er een poging
    // volgt ná degene die nu loopt. De knop staat ondertussen op disabled, dus handwerk komt hier
    // alleen uit wanneer een ánder element `data-actie="omgeving-opnieuw"` draagt en `zetInrichtenBezig`
    // hem dus niet uitzet — en dan is een melding het enige dat overblijft.
    if (inrichtLoopt) {
        if (metHand) toonMelding('Er loopt al een poging om de omgeving te lezen', null, null);
        else planInrichting(volgendeWachttijd());

        return;
    }

    // Een druk op de knop is een nieuw verzoek van de bediener: wat de lus eerder al meldde, mag
    // daarbij opnieuw gezegd worden. Anders zwijgt de knop over precies de storing waarvoor hij is.
    if (metHand) ontdubbeldInLus.clear();

    inrichtLoopt = true;

    try {
        // Meteen zichtbaar, want een uitlezing mag tot LEES_TIMEOUT_MS duren; zonder dit ziet een
        // druk op de knop er secondenlang uit alsof er niets gebeurde. Binnen de try, zodat de
        // finally de vlag hoe dan ook weer uitzet.
        zetInrichtenBezig(true);

        const gelukt = await pasOmgevingToe();

        if (!gelukt) {
            const wacht = volgendeWachttijd();

            // Een druk op de knop stelt de al geplande poging niet uit: wie blijft drukken zou de
            // automatische lus anders nooit laten afgaan. En dan ook geen wachttijd noemen die niet
            // klopt — de lopende telling staat al ergens tussen nul en dat getal.
            const opnieuwGepland = !metHand || inrichtTimer === null;

            if (opnieuwGepland) planInrichting(wacht);

            if (!metHand) inrichtPoging += 1;

            toonInrichtingsfout('Het paneel kon de omgeving niet lezen; knoppen die daarvan afhangen ' +
                'blijven uit. ' + (opnieuwGepland
                    ? 'Volgende poging over ' + Math.round(wacht / 1000) + ' seconden.'
                    : 'Het paneel blijft het zelf proberen.'));

            return;
        }

        planInrichting(null);
        toonInrichting(false);

        // Alleen als er iets te melden vált: bij een gewone start heeft niemand om deze regel
        // gevraagd, en een melding die er altijd staat leest niemand meer. "Compleet" alleen als er
        // ook echt niets openstaat — een ontbrekend element of een onleesbare lijst blijft staan,
        // ook al las deze poging de omgeving gewoon.
        // Een automatische poging die net terugkomt mag de uitkomst van de actie die de bediener
        // zojuist indrukte niet overschrijven — maar dat geldt alleen voor de bevestiging dat alles
        // klopt. Blijft er iets stuk, dan is dat het belangrijkere bericht en gaat het voor.
        const compleet = !ietsOpenstaand();

        if ((inrichtPoging > 0 || metHand) && (metHand || bezig === 0 || !compleet)) {

            toonMelding(
                compleet
                    ? 'De omgeving is gelezen; het paneel is compleet'
                    : 'De omgeving is gelezen, maar dit werkt niet: ' + watOpenstaat(),
                compleet ? 'goed' : 'let-op',
                null,
            );
        }

        inrichtPoging = 0;
    } catch (fout) {
        // Een fout in de bedrading zelf — een element dat de opmaak niet meer draagt. Die gaat niet
        // over van wachten, dus geen nieuwe poging; het blok blijft wél staan, want anders verdwijnt
        // met de enige melding ook de enige knop die er nog iets aan kan doen. `lees()` valt hier
        // niet onder: die vangt een onbereikbare console zelf af en geeft null.
        console.error('[bediening] omgeving niet toe te passen', fout);

        // Deze tak plant zelf niets; staat er nog wel een poging klaar, dan is "het paneel geeft het
        // op" een leugen die de bediener naar een refresh stuurt terwijl hij kan wachten.
        toonInrichtingsfout('Het paneel kon zichzelf niet inrichten: ' + fout + '.' +
            (inrichtTimer === null ? ' Er volgt geen automatische poging meer.' : ''));
    } finally {
        inrichtLoopt = false;

        zetInrichtenBezig(false);

        // De ingedrukte knop ging op disabled en verloor zijn focus naar <body>. Is hij nog zichtbaar,
        // dan hoort die focus terug: anders begint toetsenbordnavigatie weer bovenaan de pagina en
        // moet de bediener het hele paneel doortabben om nog eens te kunnen proberen. `offsetParent`
        // dekt zowel een verborgen blok als een ingeklapt paneel.
        if (metHand && document.activeElement === document.body && inrichtingKnop &&
            inrichtingKnop.offsetParent !== null) {
            inrichtingKnop.focus();
        }
    }
}

function volgendeWachttijd() {
    return INRICHT_WACHT[Math.min(inrichtPoging, INRICHT_WACHT.length - 1)];
}

function toonInrichtingsfout(tekst) {
    toonInrichting(true);

    if (inrichting) inrichting.className = 'melding melding--fout';

    if (inrichtingTekst) {
        inrichtingTekst.textContent = tekst;

        return;
    }

    registreerPaneelfout('de tekstregel van het inrichtingsblok');

    // Zonder die regel blijft het blok leeg, dus draagt de meldingsbalk de oorzaak — met de
    // opmaakfout in dezelfde zin, want twee meldingen achter elkaar laten er maar één over.
    toonMelding(tekst + ' Het paneel mist bovendien onderdelen van zijn eigen opmaak.', 'fout', null);
}

function toonInrichting(zichtbaar) {
    if (inrichting) inrichting.hidden = !zichtbaar;
    else registreerPaneelfout('het blok voor een mislukte inrichting');

    // Ook zonder blok, en ook bij een storing die alleen in de meldingsbalk staat.
    markeerKlap(zichtbaar || ietsOpenstaand());
}

/* De knop uit terwijl zijn eigen poging loopt, met de tekst en de rand van het blok erop afgestemd:
 * een tweede poging naast de eerste zou alleen elkaars uitkomst overschrijven. */
function zetInrichtenBezig(bezigMetInrichten) {
    if (inrichtingKnop) inrichtingKnop.disabled = bezigMetInrichten;

    if (!bezigMetInrichten || !inrichtingTekst) return;

    inrichtingTekst.textContent = 'Bezig de omgeving te lezen…';

    // Een lopende poging is geen storing; de rode rand van het blok zou zeggen dat er nu iets mis is.
    if (inrichting) inrichting.className = 'melding';
}

/* Eén timer voor de volgende poging, en die wordt altijd eerst gewist. Zonder dat wissen laat een
 * druk op de knop terwijl er al een poging gepland stond twee timers achter: vanaf dan verdubbelt
 * het aantal pogingen bij elke ronde. `null` plant niets en wist alleen. */
function planInrichting(wacht) {
    clearTimeout(inrichtTimer);

    // De id wist zichzelf zodra de timer afgaat: `inrichtTimer` moet "er staat een poging klaar"
    // betekenen, en niet "er is er ooit een gepland" — daar hangt de belofte aan de bediener aan.
    // `richtIn()` zonder argument, dus als automatische poging.
    inrichtTimer = wacht === null ? null : setTimeout(() => {
        inrichtTimer = null;

        richtIn();
    }, wacht);
}

/* De ontdubbeling loopt op een BSN, dus alleen persona's met een BSN kunnen hem spelen. Een vrij
 * tekstveld zou een BSN vragen die verderop in dezelfde pagina al als keuzelijst bestaat.
 *
 * De optie draagt de persona-id en niet zijn nummer: het adres dat de knop aanroept hoort geen
 * identificatienummer te dragen. De console zoekt het nummer zelf op. */
function vulPersonas(personas, uitlezingGelukt) {
    const keuze = document.getElementById('ontdubbelPersona');
    const knop = document.querySelector('button[data-samenvatting="ontdubbeling"]');

    if (!Array.isArray(personas)) {
        meldOnbruikbareLijst('personas', personas, keuze, knop, 'ontdubbelPersona', uitlezingGelukt);

        return;
    }

    if (!heeftAlleVelden('personas', personas, ['id', 'ontvanger'], keuze, knop, 'ontdubbelPersona')) return;

    const metBsn = personas.filter((persona) => persona.ontvanger.startsWith('BSN:'));

    vulKeuze(
        keuze,
        knop,
        metBsn.map((persona) => ({ waarde: persona.id, label: persona.label })),
        'geen persona met een BSN ingericht',
        'ontdubbelPersona',
    );
}

/* Zonder `id` wordt de optie-wáárde de string "undefined" terwijl het label gewoon klopt: de lijst
 * ziet er goed uit, de knop blijft levend, en zijn 404 wijst naar de persona-inrichting waar niets
 * mis is. Zonder `ontvanger` gooit het filter hierboven een TypeError, die de aanroeper opvangt en
 * als "niet op te halen" toont — dezelfde verkeerde diagnose, in de andere richting. Vandaar een
 * lijst met de velden die de aanroeper nodig heeft: `berichtPersonas` draagt bewust geen ontvanger.
 *
 * Een eigen melding en niet die van `meldOnbruikbareLijst`: de lijst kwám binnen en was wél op te
 * halen, er ontbreekt een veld. Met de verkeerde reden afhaken stuurt de bediener de andere kant
 * op. Het label van de eerste die iets mist wijst de inrichting aan; de lijst zelf blijft uit de
 * console-regel, want daar staan de identificatienummers in. */
function heeftAlleVelden(bron, personas, velden, keuze, knop, keuzeId) {
    // `!persona ||` vóór de rest: een null in de lijst zou anders een TypeError geven, die de
    // aanroeper opvangt en als "niet als lijst binnengekomen" toont — weer de verkeerde diagnose.
    // En `findIndex` en niet `find`: die geeft bij precies dat null-element een falsy waarde terug,
    // waarna de regel hieronder de lijst goedkeurt.
    const plek = personas.findIndex((persona) => !persona || velden.some((veld) => !persona[veld]));

    if (plek === -1) return true;

    const onvolledig = personas[plek];

    // Wélk veld, want de twee wijzen naar verschillende plekken in de inrichting: een ontbrekende
    // `id` naar de sleutel onder demo.personas, een ontbrekende `ontvanger` naar type en waarde.
    const gemist = (onvolledig && velden.find((veld) => !onvolledig[veld])) || 'de persona zelf';
    const wie = (onvolledig && onvolledig.label) || 'zonder label';

    console.error('[bediening] ' + bron + ': persona "' + wie + '" mist ' + gemist);
    toonMelding('In de persona-lijst ' + bron + ' mist "' + wie + '" het veld ' + gemist, 'fout', null);
    meldLijstOnbekend(keuze, knop, keuzeId, 'persona-lijst onvolledig');

    return false;
}

/* De persona's waarvoor de console kán aanleveren; de uitvraag levert die deelverzameling apart.
 * De waarde is de persona-id, want die gaat als queryparameter mee. */
function vulBerichtPersonas(personas, uitlezingGelukt) {
    const keuze = document.getElementById('berichtPersona');
    const knop = document.getElementById('berichtKnop');

    if (!Array.isArray(personas)) {
        meldOnbruikbareLijst('berichtPersonas', personas, keuze, knop, 'berichtPersona', uitlezingGelukt);

        return;
    }

    if (!heeftAlleVelden('berichtPersonas', personas, ['id'], keuze, knop, 'berichtPersona')) return;

    vulKeuze(
        keuze,
        knop,
        personas.map((persona) => ({ waarde: persona.id, label: persona.label })),
        'geen persona met een magazijn ingericht',
        'berichtPersona',
    );
}

/* `!Array.isArray` vat twee oorzaken samen: er kwam geen antwoord (`lees()` geeft dan `null`), of er
 * kwam er wél een waarin dit veld ontbreekt of van vorm veranderd is — een console die het nog niet
 * kent, of een proxy die het herschrijft. `uitlezingGelukt` is het enige dat die twee uit elkaar
 * houdt, en alleen het tweede geval is een dode bediening die blijft staan. Altijd loggen, ook bij
 * `null`: anders is "de console antwoordde met null" van de andere twee niet te onderscheiden. */
function meldOnbruikbareLijst(veld, waarde, keuze, knop, keuzeId, uitlezingGelukt) {
    // De vorm en niet de waarde: die kan het antwoord zijn dát binnenkwam, met de
    // identificatienummers van de persona's erin, en de browserconsole is daar geen plek voor.
    // `null` apart, want `typeof null` is `'object'` — juist het onderscheid dat hierboven de reden
    // is om altijd te loggen zou anders wegvallen.
    const vorm = waarde === null ? 'null' : typeof waarde;

    console.error('[bediening] ' + veld + ' is niet als lijst binnengekomen, maar ' + vorm);

    // Alleen wanneer het antwoord binnenkwam maar dit veld miste: dán is de bediening dood terwijl de
    // omgeving verder gelezen is, en zou een volgende geslaagde poging het paneel ten onrechte
    // compleet noemen. Kwam er helemaal geen antwoord, dan zegt het inrichtingsblok dat al, en gaat
    // deze lijst vanzelf mee zodra de console terug is.
    if (uitlezingGelukt) {
        onbruikbareLijsten.add(LIJSTNAMEN[veld] || veld);

        markeerKlap(true);
    }

    meldLijstOnbekend(keuze, knop, keuzeId);
}

/* De lijst niet kunnen lezen is iets anders dan niets ingericht hebben: met de verkeerde reden
 * afhaken stuurt de bediener de configuratie in terwijl de console even weg was.
 *
 * De knop gaat uit, net als bij een lege lijst. Zonder persona kan hij niets versturen — de optie
 * draagt een lege `value` en `vulPadIn` houdt de klik dan tegen — en een knop die er levend uitziet
 * en zwijgend niets doet is tijdens een demo erger dan een knop die zichtbaar niet kan. Die lege
 * `value` is er omdat een optie zonder dat attribuut haar tékst als waarde draagt: de knop stuurde
 * dan `?persona=persona-lijst niet op te halen` en kreeg een 404 die naar de persona-inrichting
 * wijst in plaats van naar de mislukte uitlezing. `tekst` scherpt die reden aan waar de aanroeper
 * hem kent; de lege `value` blijft in alle gevallen.
 *
 * Ontbreekt een element, dan is de opmaak veranderd zonder dit script. Dat gaat naar de
 * meldingsbalk en niet alleen naar de console: wie een demo geeft heeft geen devtools open. */
function meldLijstOnbekend(keuze, knop, keuzeId, tekst) {
    if (knop) zetWachtOpLijst(knop, true);

    meldOntbrekendeLijst(keuze, knop, keuzeId);

    if (!keuze) return;

    const onbekend = document.createElement('option');

    onbekend.value = '';
    onbekend.textContent = tekst || 'persona-lijst niet op te halen';

    keuze.replaceChildren(onbekend);
    keuze.disabled = true;
}

/* Drie sets, elk met een eigen levensduur. `openstaandePaneelfouten` is wat er blijvend mis is: een
 * element dat de opmaak niet draagt. Samen met `onbruikbareLijsten` (verderop) bepaalt die via
 * `ietsOpenstaand()` het merkteken op de klap-knop en of het paneel zichzelf compleet mag noemen.
 * `ontdubbeldInLus` is wat de herhalende inricht-lus al gezegd heeft — die komt hetzelfde ontbrekende
 * element bij elke poging opnieuw tegen, en de meldingsbalk houdt de uitkomst van de laatste actie
 * vast.
 *
 * De vlag zit op de aanroepplekken die de lus doorloopt; de knop "Nu opnieuw proberen" raakt diezelfde
 * plekken, en daarom leegt `richtIn` bij handwerk de tweede set. De eerste blijft staan: een ontbrekend
 * invoerveld gaat niet over van een uitlezing die dat veld niet eens bekijkt. */
const openstaandePaneelfouten = new Set();
const ontdubbeldInLus = new Set();

/* En apart daarvan: welke persona-lijsten niet bruikbaar binnenkwamen. Die storing kan overgaan — de
 * volgende inricht-poging bekijkt precies die lijst opnieuw — dus stelt elke poging hem opnieuw vast
 * in plaats van hem voor de rest van de sessie vast te houden. Ná een geslaagde ronde komt die
 * volgende poging alleen nog van de knop of van een refresh; de automatische lus is dan gestopt. */
const onbruikbareLijsten = new Set();

/* Of er nog iets openstaat: dat bepaalt het merkteken op de klap-knop en of het paneel zichzelf
 * compleet mag noemen. */
function ietsOpenstaand() {
    return openstaandePaneelfouten.size > 0 || onbruikbareLijsten.size > 0;
}

function watOpenstaat() {
    return opsom(Array.from(openstaandePaneelfouten).concat(Array.from(onbruikbareLijsten)));
}

/* Vastleggen zonder te melden, voor wie de storing zelf in zijn eigen zin verwoordt. `wat` is een
 * naamwoordgroep ("de tabbladenrij"), zodat `watOpenstaat()` er een opsomming van kan maken. */
function registreerPaneelfout(wat) {
    console.error('[bediening] werkt niet:', wat);

    openstaandePaneelfouten.add(wat);

    // De melding staat in het paneel, en dat kan ingeklapt zijn.
    markeerKlap(true);
}

/* De id erbij: er zijn twee keuzelijsten, en "een keuzelijst ontbreekt" laat de bediener niet zien
 * wélke bediening dood is. In één melding en niet in twee: de meldingsbalk toont er maar één, dus
 * bij twee aanroepen blijft de eerste helft van de storing in het log hangen. */
function meldOntbrekendeLijst(keuze, knop, keuzeId) {
    const mist = [];

    if (!keuze) mist.push('de keuzelijst ' + keuzeId);

    if (!knop) mist.push('de knop bij keuzelijst ' + (keuze ? keuze.id : keuzeId));

    if (mist.length) meldOpmaakfout(opsom(mist), true);
}

function meldOpmaakfout(wat, uitDeLus) {
    // Ná de ontdubbelings-guard, anders herhaalt elke ronde hetzelfde log en merkteken; vóór de
    // melding, zodat de eerste aanroep die er langskomt de storing vastlegt en `openstaandePaneelfouten`
    // hem vasthoudt ook wanneer latere rondes zwijgen.
    if (uitDeLus && ontdubbeldInLus.has(wat)) return;

    registreerPaneelfout(wat);

    if (uitDeLus) ontdubbeldInLus.add(wat);

    toonMelding('Het paneel mist ' + wat + ' in zijn opmaak; die bediening werkt niet', 'fout', null);
}

function vulKeuze(keuze, knop, opties, leegTekst, keuzeId) {
    // Dezelfde toets als in meldLijstOnbekend: zonder deze zou een ontbrekend element hier een
    // TypeError geven, die de aanroeper opvangt en als "lijst niet op te halen" toont — terwijl de
    // lijst gewoon binnenkwam. Precies de verkeerde diagnose, in de andere richting.
    if (!keuze || !knop) {
        meldOntbrekendeLijst(keuze, knop, keuzeId);

        return;
    }

    /* Een optie-waarde komt via `vulPadIn` in het adres van de knop terecht, en daarmee in
     * browsergeschiedenis, proxylogboeken en schermopnames. Een identificatienummer hoort daar niet.
     * Hier afgedwongen en niet bij elke aanroeper: dit is de enige plek waar een keuzelijst van dit
     * script zijn opties krijgt, dus een volgende lijst erft de regel vanzelf.
     *
     * Bewust smaller dan de weigering aan de serverkant: die kijkt naar een aangeboden waarde en
     * moet elke schrijfwijze aankunnen, deze vangt de vergissing waarbij een lijst het
     * identificatienummer als waarde neemt in plaats van de id. Dat is een kale reeks of
     * `TYPE:reeks`, geankerd, zodat een ingerichte id die toevallig cijfers draagt met rust blijft.
     * Acht of negen cijfers: een KVK-nummer, BSN of RSIN. Een OIN is er twintig en publiek — een
     * keuzelijst van magazijnen mag die gewoon dragen. Het label mag in de melding, de waarde niet. */
    const metNummer = opties.find((optie) => /^(?:[A-Za-z]+:)?\d{8,9}$/.test(String(optie.waarde)));

    if (metNummer) {
        console.error('[bediening] keuzelijst ' + keuze.id + ': optie "' + metNummer.label + '" draagt een nummer');
        toonMelding('De lijst voor ' + keuze.id + ' draagt een identificatienummer als waarde', 'fout', null);
        meldLijstOnbekend(keuze, knop, keuzeId, 'persona-lijst bevat een nummer');

        return;
    }

    keuze.replaceChildren();
    keuze.disabled = false;
    zetWachtOpLijst(knop, false);

    if (!opties.length) {
        const leeg = document.createElement('option');

        // Lege `value` om dezelfde reden als in meldLijstOnbekend: een optie zonder dat attribuut
        // draagt haar tékst als waarde, en dan stuurt de knop die zin als persona-id mee. Dat de
        // knop hieronder uit gaat is de eerste borging; dit is de tweede.
        leeg.value = '';
        leeg.textContent = leegTekst;

        keuze.append(leeg);
        keuze.disabled = true;
        zetWachtOpLijst(knop, true);

        return;
    }

    opties.forEach((optie) => {
        const element = document.createElement('option');

        element.value = optie.waarde;
        element.textContent = optie.label;
        keuze.append(element);
    });

    // De id van het <select> is tegelijk zijn sleutel in VELDEN; staat hij daar niet, dan bewaart
    // het paneel niets en herstelt deze regel stil niets.
    const bewaard = (leesStand().velden || {})[keuze.id];

    // Een persona die er niet meer is — andere configuratie, andere personaset — valt terug op
    // de eerste in de lijst in plaats van op een lege keuze die de knop laat falen.
    if (bewaard && Array.from(keuze.options).some((optie) => optie.value === bewaard)) {
        keuze.value = bewaard;
    }
}

// ---------------------------------------------------------------- info-blad

/* Van gezond naar kapot, zodat de telling bij elke uitlezing dezelfde vorm heeft. Een modus die hier
 * ontbreekt valt niet weg maar komt achteraan. */
const MODUS_VOLGORDE = ['NORMAAL', 'TRAAG', 'HAPERT', 'WEIGERT', 'MALFORMED', 'STUK', 'UIT'];

/* Wat elk blok op het Info-blad tekent. `bewaarbaar` snoeit een antwoord vóór het in het geheugen en
 * in sessionStorage belandt; een blok zonder die functie bewaart het antwoord zoals het binnenkwam. */
const INFO_BLOKKEN = {
    berichten: { teken: tekenBerichten },
    simulator: { teken: tekenSimulator },
    personas: { teken: tekenPersonas, bewaarbaar: personaInfo },
    stroom: { teken: tekenStroom },
    storingen: { teken: tekenStoringen },
    componenten: { teken: tekenComponenten },
};

/* `null` is een mislukte uitlezing: de vorige inhoud blijft dan staan en alleen het tijdlabel zegt
 * dat hij verouderd is. Juist tijdens een storing wil je de laatste bekende stand nog zien. */
function werkInfoBij(sleutel, antwoord) {
    const stand = infoStand[sleutel] || (infoStand[sleutel] = {});

    if (antwoord === null) {
        stand.mislukt = true;
    } else {
        const blok = INFO_BLOKKEN[sleutel];
        const nieuw = blok.bewaarbaar ? blok.bewaarbaar(antwoord) : antwoord;

        // Hetzelfde antwoord elke vijf seconden opnieuw tekenen laat een schermlezer die door de lijst
        // loopt telkens bovenaan beginnen.
        const veranderd = JSON.stringify(nieuw) !== JSON.stringify(stand.inhoud);

        stand.inhoud = nieuw;
        stand.tijd = Date.now();
        stand.mislukt = false;

        if (veranderd) tekenInfo(sleutel);

        bewaarInfo();
    }

    toonInfoTijd(sleutel);
}

function tekenInfo(sleutel) {
    const doel = document.getElementById('info-' + sleutel + '-inhoud');

    if (!doel) {
        meldOpmaakfout('het info-blok ' + sleutel, true);

        return;
    }

    try {
        INFO_BLOKKEN[sleutel].teken(doel, infoStand[sleutel].inhoud);
    } catch (fout) {
        // In het blok zelf en niet alleen in de console: een lege of half bijgewerkte lijst leest
        // anders als een echte stand.
        console.error('[bediening] info-blok ' + sleutel + ' niet te tekenen', fout);
        doel.replaceChildren(infoAlinea('Het antwoord had een onverwachte vorm; zie de browserconsole'));
    }
}

/* Zonder `aria-live`: dit label verandert elke seconde, en een schermlezer die dat voorleest laat de
 * melding van een actie er niet meer tussen. */
function toonInfoTijd(sleutel) {
    const label = document.getElementById('info-' + sleutel + '-tijd');

    if (!label) {
        meldOpmaakfout('het tijdlabel van info-blok ' + sleutel, true);

        return;
    }

    const stand = infoStand[sleutel] || {};

    let tekst = 'nog niet gelezen';

    if (stand.tijd && stand.mislukt) {
        tekst = 'laatste poging mislukt · bijgewerkt ' + geleden(stand.tijd);
    } else if (stand.tijd) {
        tekst = 'bijgewerkt ' + geleden(stand.tijd);
    } else if (stand.mislukt) {
        tekst = 'niet te lezen';
    }

    label.textContent = tekst;
    label.dataset.soort = stand.mislukt ? 'let-op' : '';
}

function geleden(tijd) {
    const seconden = Math.max(0, Math.round((Date.now() - tijd) / 1000));

    if (seconden < 10) return 'zojuist';

    if (seconden < 60) return seconden + ' s geleden';

    if (seconden < 3600) return Math.floor(seconden / 60) + ' min geleden';

    return 'om ' + new Date(tijd).toLocaleTimeString('nl-NL', { hour: '2-digit', minute: '2-digit' });
}

/* Alleen tijd en inhoud; de persona's zijn daarin al door `personaInfo` ontdaan van hun nummers.
 * Storage kan gooien wanneer site-data geblokkeerd is; het blad werkt dan zonder geheugen. */
function bewaarInfo() {
    const bewaard = {};

    Object.entries(infoStand).forEach(([sleutel, stand]) => {
        if (stand.tijd) bewaard[sleutel] = { tijd: stand.tijd, inhoud: stand.inhoud };
    });

    try {
        sessionStorage.setItem(INFO_SLEUTEL, JSON.stringify(bewaard));
    } catch (fout) {
        return;
    }
}

/* Een onleesbare bewaarde stand begint gewoon leeg; een bewaard blok dat INFO_BLOKKEN niet meer kent, valt weg.
 * Een bewaarde inhoud in een vorm die de huidige tekenaar niet begrijpt, meldt `tekenInfo` zelf. */
function herstelInfo() {
    let bewaard;

    try {
        bewaard = JSON.parse(sessionStorage.getItem(INFO_SLEUTEL)) || {};
    } catch (fout) {
        bewaard = {};
    }

    Object.keys(INFO_BLOKKEN).forEach((sleutel) => {
        const stand = bewaard[sleutel];

        if (stand && typeof stand.tijd === 'number') {
            infoStand[sleutel] = { inhoud: stand.inhoud, tijd: stand.tijd, mislukt: false };
            tekenInfo(sleutel);
        }

        toonInfoTijd(sleutel);
    });
}

function infoInBeeld() {
    const blad = document.getElementById('blad-info');

    return Boolean(blad) && !blad.hidden && !document.body.classList.contains('ingeklapt');
}

/* Loopt elke seconde, maar doet alleen iets terwijl iemand naar het Info-blad kijkt: daarbuiten leest
 * niemand "… geleden", en de simulatorlijst wacht dan tot het blad weer open gaat. */
function tikInfo() {
    if (document.hidden || !infoInBeeld()) return;

    Object.keys(INFO_BLOKKEN).forEach(toonInfoTijd);

    const teOud = Date.now() - laatsteSimulatorPoging >= SIMULATOR_INFO_MS;

    // `true` en niet alleen niet-`false`: zolang het inrichten niet terug is, is onbekend of er een
    // simulator is. En niet midden in een actie, om dezelfde reden als bij de toestandsbalk.
    if (heeftSimulator === true && bezig === 0 && (simulatorVerlopen || teOud)) verversSimulator(false);
}

async function verversSimulator(metHand) {
    if (bezig > 0) {
        if (metHand) toonMelding('Er loopt nog een actie; de lijst komt daarna vanzelf bij', null, null);

        return;
    }

    const beurt = ++simulatorBeurt;

    laatsteSimulatorPoging = Date.now();
    simulatorVerlopen = false;

    const magazijnen = await lees('/api/demo/simulator/magazijnen');

    // Een nieuwere poging — een klik, of de planning na een actie — gaat voor.
    if (beurt !== simulatorBeurt) return;

    const bruikbaar = Array.isArray(magazijnen);

    if (magazijnen !== null && !bruikbaar) console.error('[bediening] simulatorlijst is geen lijst maar ' + typeof magazijnen);

    werkInfoBij('simulator', bruikbaar ? magazijnen : null);

    if (metHand && !bruikbaar) {
        toonMelding('De gesimuleerde magazijnen waren niet te lezen; de vorige stand blijft staan', 'let-op', null);
    }
}

/* Een ↻ die een uitlezing start, draait tot die terug is. Zonder dat is een druk erop niet te
 * onderscheiden van een klik die niets deed: het tijdlabel verspringt pas als het antwoord er is. */
function draaiTot(knop, loopt) {
    knop.dataset.bezig = 'ja';

    loopt.finally(() => {
        delete knop.dataset.bezig;
    });
}

/* Een antwoord zonder de vorm die een blok tekent, gooit hier; `tekenInfo` meldt dat dan in het blok
 * in plaats van een half getekende stand te tonen. */
function vereisObject(waarde) {
    if (!waarde || typeof waarde !== 'object' || Array.isArray(waarde)) {
        throw new TypeError('geen object maar ' + (Array.isArray(waarde) ? 'een lijst' : typeof waarde));
    }
}

function infoLijst(rijen) {
    const lijst = document.createElement('dl');

    lijst.className = 'infolijst';

    rijen.forEach(([term, waarde, soort]) => {
        const dt = document.createElement('dt');
        const dd = document.createElement('dd');

        dt.textContent = term;
        dd.textContent = waarde;

        if (soort) dd.dataset.soort = soort;

        lijst.append(dt, dd);
    });

    return lijst;
}

function infoAlinea(tekst) {
    const alinea = document.createElement('p');

    alinea.className = 'uitleg';
    alinea.textContent = tekst;

    return alinea;
}

function tekenBerichten(doel, status) {
    vereisObject(status);

    const aantallen = Object.entries(status);

    if (!aantallen.length) {
        doel.replaceChildren(infoAlinea('Geen magazijnen ingericht'));

        return;
    }

    const totaal = aantallen.reduce((som, [, aantal]) => som + aantal, 0);

    doel.replaceChildren(infoLijst(
        aantallen.map(([sleutel, aantal]) => [naam(sleutel), getal(aantal)]).concat([['Totaal', getal(totaal)]]),
    ));
}

/* Met punt als duizendtal-scheiding: bij honderd gesimuleerde magazijnen loopt een totaal al snel in
 * de duizenden, en 2646 leest dan als een jaartal. */
function getal(aantal) {
    return Number(aantal).toLocaleString('nl-NL');
}

function tekenStroom(doel, tempo) {
    vereisObject(tempo);

    doel.replaceChildren(infoLijst(tempo.loopt
        ? [['Stroom', 'loopt', 'let-op'], ['Interval', 'elke ' + tempo.intervalSeconden + ' s'], ['Geleverd', String(tempo.geleverd)]]
        : [['Stroom', 'uit']]));
}

function tekenStoringen(doel, storingen) {
    vereisObject(storingen);

    const proxies = Object.entries(storingen);

    doel.replaceChildren(proxies.length
        ? infoLijst(proxies.map(([proxy, toestand]) => [onderdeelNaam(proxy), String(toestand), toestand === 'normaal' ? null : 'fout']))
        : infoAlinea('Deze omgeving heeft geen storingsproxies'));
}

/* Of het component zelf antwoordt, naast de storingen hierboven: die zeggen wat Toxiproxy op de lijn
 * zet. Een magazijn zonder proxy dat plat ligt, is alleen hier te zien. */
function tekenComponenten(doel, bereikbaarheid) {
    vereisObject(bereikbaarheid);

    const componenten = Object.entries(bereikbaarheid);

    doel.replaceChildren(componenten.length
        ? infoLijst(componenten.map(([component, toestand]) => [
            onderdeelNaam(component),
            String(toestand).replace('-', ' '),
            toestand === 'bereikbaar' ? null : 'fout',
        ]))
        : infoAlinea('Deze omgeving controleert geen componenten'));
}

function onderdeelNaam(sleutel) {
    return ONDERDEEL_NAMEN[sleutel] || naam(sleutel);
}

/* Eerst alles uitrekenen en pas daarna de pagina raken: een element dat geen object is, gooit dan
 * vóór er een telling staat die niet bij de tabel eronder hoort. */
function tekenSimulator(doel, magazijnen) {
    if (!Array.isArray(magazijnen)) throw new TypeError('geen lijst maar ' + typeof magazijnen);

    const details = document.getElementById('info-simulator-details');
    const samenvatting = document.getElementById('info-simulator-samenvatting');
    const rijen = document.getElementById('info-simulator-rijen');

    if (!details || !samenvatting || !rijen) {
        meldOpmaakfout('de tabel van gesimuleerde magazijnen', true);

        return;
    }

    const telling = {};

    magazijnen.forEach((magazijn) => {
        telling[magazijn.modus] = (telling[magazijn.modus] || 0) + 1;
    });

    const modi = MODUS_VOLGORDE.filter((modus) => telling[modus])
        .concat(Object.keys(telling).filter((modus) => !MODUS_VOLGORDE.includes(modus)));

    // Afwijkend eerst: dat zijn de regels waar je in een demo naar zoekt. `sort` is stabiel, dus binnen
    // beide groepen blijft de OIN-volgorde van de console staan.
    const tabelrijen = magazijnen
        .slice()
        .sort((een, ander) => Number(een.modus === 'NORMAAL') - Number(ander.modus === 'NORMAAL'))
        .map(simulatorRij);

    // Alleen een totaal als elk magazijn zijn aantal meegeeft: een som over een half getelde lijst
    // leest als een echte telling.
    const aantallen = magazijnen.map((magazijn) => magazijn.berichten);
    const totaal = aantallen.every((aantal) => typeof aantal === 'number')
        ? [['Berichten', getal(aantallen.reduce((som, aantal) => som + aantal, 0))]]
        : [];

    doel.replaceChildren(magazijnen.length
        ? infoLijst(totaal.concat(modi.map((modus) => [
            modusNaam(modus),
            getal(telling[modus]) + (telling[modus] === 1 ? ' magazijn' : ' magazijnen'),
            modus === 'NORMAAL' ? null : 'let-op',
        ])))
        : infoAlinea('De simulator stelt geen magazijnen voor'));

    samenvatting.textContent = 'Alle ' + magazijnen.length + ' magazijnen';
    rijen.replaceChildren(...tabelrijen);
    details.hidden = magazijnen.length === 0;
}

function simulatorRij(magazijn) {
    const rij = document.createElement('tr');

    const berichten = typeof magazijn.berichten === 'number' ? getal(magazijn.berichten) : 'onbekend';

    [magazijn.naam, magazijn.oin, modusNaam(magazijn.modus), berichten].forEach((waarde) => {
        const cel = document.createElement('td');

        cel.textContent = waarde;
        rij.append(cel);
    });

    if (magazijn.modus !== 'NORMAAL') rij.dataset.soort = 'let-op';

    return rij;
}

function modusNaam(modus) {
    return String(modus).toLowerCase();
}

/* Een tabel en geen opsomming: de tweede kolom beantwoordt waarom *Bericht plaatsen* een persona niet
 * aanbiedt. Het teken staat nooit alleen; de tekst ernaast zegt hetzelfde. */
function tekenPersonas(doel, personas) {
    if (!Array.isArray(personas)) throw new TypeError('geen lijst maar ' + typeof personas);

    if (!personas.length) {
        doel.replaceChildren(infoAlinea("Geen persona's ingericht"));

        return;
    }

    const rijen = personas.map((persona) => {
        const rij = document.createElement('tr');
        const naamcel = document.createElement('td');
        const magazijncel = document.createElement('td');
        const teken = document.createElement('span');

        naamcel.textContent = persona.label;
        teken.className = 'infoteken';
        teken.setAttribute('aria-hidden', 'true');
        teken.textContent = persona.metMagazijn ? '✓' : '—';
        teken.dataset.soort = persona.metMagazijn ? 'goed' : '';
        magazijncel.append(teken, persona.metMagazijn ? 'ja' : 'nee, alleen gesimuleerde');
        rij.append(naamcel, magazijncel);

        return rij;
    });

    const tabel = document.createElement('table');
    const kop = document.createElement('thead');
    const kopregel = document.createElement('tr');
    const romp = document.createElement('tbody');
    const omhulsel = document.createElement('div');

    ['Persona', 'Echt magazijn'].forEach((titel) => {
        const cel = document.createElement('th');

        cel.scope = 'col';
        cel.textContent = titel;
        kopregel.append(cel);
    });

    kop.append(kopregel);
    romp.append(...rijen);
    tabel.append(kop, romp);
    omhulsel.className = 'infotabel';
    omhulsel.append(tabel);

    doel.replaceChildren(
        omhulsel,
        infoAlinea("Alleen persona's met een echt magazijn staan in de keuzelijst van Bericht plaatsen."),
    );
}

/* Per persona alleen het label en of er een echt magazijn voor is. Nooit de persona zelf: die draagt
 * ook zijn BSN of KVK-nummer, en dit belandt in sessionStorage. De id koppelt de twee lijsten en gaat
 * niet mee. */
function personaInfo(omgeving) {
    if (!omgeving || typeof omgeving !== 'object' || !Array.isArray(omgeving.personas)) return null;

    const metMagazijn = new Set((Array.isArray(omgeving.berichtPersonas) ? omgeving.berichtPersonas : [])
        .map((persona) => persona && persona.id));

    return omgeving.personas
        .filter((persona) => persona && persona.label)
        .map((persona) => ({ label: persona.label, metMagazijn: metMagazijn.has(persona.id) }));
}

// ---------------------------------------------------------------- bedrading

const LOSSE_ACTIES = {
    klap: klap,
    'ververs-box': verversBox,
    'ververs-toestand': () => verversToestand(true),
    'ververs-simulator': () => verversSimulator(true),
    'omgeving-opnieuw': () => richtIn(true),
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
        // `hasOwn` en niet een kale lookup: een naam als `toString` levert anders een geërfde functie
        // op die wordt aangeroepen in plaats van gemeld. Zonder deze toets geeft een onbekende naam
        // een TypeError, die het vangnet als "onverwachte fout" toont — hier staat de naam die niet
        // klopt. Geen `meldOpmaakfout`: de opmaak draagt die naam juist wél, het script kent hem niet.
        if (!Object.hasOwn(LOSSE_ACTIES, knop.dataset.actie)) {
            registreerPaneelfout('de knop met de actie ' + knop.dataset.actie);
            toonMelding('Deze knop noemt een actie die het paneel niet kent: ' + knop.dataset.actie, 'fout', null);

            return;
        }

        const loopt = LOSSE_ACTIES[knop.dataset.actie]();

        if (loopt instanceof Promise) draaiTot(knop, loopt);
    } else if (knop.dataset.bevestig) {
        vraagBevestiging(knop);
    } else {
        voerUit(knop);
    }
});

document.querySelectorAll('[role="tab"]').forEach((tab) => {
    tab.dataset.label = tab.textContent.trim();
});

const tablijst = document.querySelector('[role="tablist"]');

if (tablijst) tablijst.addEventListener('keydown', tabToets);
else meldOpmaakfout('de tabbladenrij');

// `input` en niet `change`: bij `change` gaat een getal dat je net intikte verloren zodra je de
// pagina herlaadt zonder eerst het veld te verlaten.
const paneelblok = document.getElementById('paneel');

if (paneelblok) {
    paneelblok.addEventListener('input', (gebeurtenis) => {
        if (VELDEN.includes(gebeurtenis.target.id)) bewaarVelden();
    });
} else {
    meldOpmaakfout('het paneel zelf');
}

// Het merkteken hoort bij elke actieknop; het hier aanhangen scheelt dezelfde span bij elke knop in
// de opmaak — en voorkomt dat één knop hem mist en als enige niets laat zien.
document.querySelectorAll('button[data-pad]').forEach((knop) => {
    const merk = document.createElement('span');

    merk.className = 'knop__uitkomst';
    knop.append(merk);
});

// Vóór het herstellen van het tabblad en het inrichten: die tekenen hun eerste uitkomst over deze
// bewaarde stand heen, en niet andersom.
herstelInfo();

// In een eigen try: `kiesTab` raakt een tabblad uit de bewaarde stand, en een hernoemd blad zou hier
// het hele script stoppen — vóór het inrichten, de poll en de knop die dat opnieuw kan proberen.
try {
    herstelStand();
} catch (fout) {
    console.error('[bediening] bewaarde stand niet te herstellen', fout);
    meldOpmaakfout('een tabblad uit de bewaarde stand');
}

// Fire-and-forget, en dat mag: `richtIn` handelt zowel een onbereikbare console als een fout in de
// eigen bedrading zelf af, en plant waar dat zin heeft een nieuwe poging. Wat er alsnog uit komt,
// vangt de unhandledrejection-listener hierboven.
richtIn(false);

verversToestand();

// Ná `verversToestand()`: die zet bij een geslaagde ronde zelf niets in de balk, dus deze melding
// blijft staan. Zonder dit is een geslaagd herstel niet te onderscheiden van een demo waarin niets
// gebeurde — het herstel navigeert immers weg, en de melding van vóór die navigatie is dan weg.
const herstelmelding = muurHerstelMelding();

if (herstelmelding) toonMelding(herstelmelding, 'let-op', null);

// Alleen pollen terwijl er iemand kijkt: een demo-console blijft dagen in een tab openstaan.
setInterval(() => {
    if (!document.hidden) verversToestand();
}, POLL_MS);

setInterval(tikInfo, TIK_MS);
