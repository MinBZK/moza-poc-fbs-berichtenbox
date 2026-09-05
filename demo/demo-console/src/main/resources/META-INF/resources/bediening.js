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

const VELDEN = ['aantal', 'tempoInterval', 'actiefAantal', 'ontdubbelPersona', 'berichtPersona', 'berichtAantal'];
const POLL_MS = 5000;
const UITKOMST_MS = 4000;

/* Korter dan POLL_MS, zodat hangende uitlezingen niet op elkaar stapelen. De toestandsbalk zou ook
 * zonder timeout niet verouderen — die kent een beurt-guard — maar het inrichten van de omgeving
 * kent die niet en blijft zonder deze grens onbeperkt hangen. */
const LEES_TIMEOUT_MS = 4000;

/* Wachttijden tussen twee pogingen om de omgeving te lezen; de laatste geldt voor alles daarna. */
const INRICHT_WACHT = [2000, 5000, 15000, 30000];

/* Sleutels zoals de API ze gebruikt, namen zoals ze in de demo genoemd worden. Alleen hier: de
 * knoppen en de statusbalk mogen niet ieder hun eigen vertaling verzinnen. */
const MAGAZIJN_NAMEN = {
    'magazijn-a': 'RVO',
    'magazijn-b': 'Bel.dienst',
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

    werkKlapBij();
    bewaarStand({ ingeklapt: ingeklapt });
}

/* Het teken, het bijschrift en het merkteken van de klap-knop staan hier bij elkaar: het merkteken
 * moet een in- en uitklap overleven, en het bijschrift wordt bij allebei opnieuw geschreven. */
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
    // Draagt de opmaak de meldingsbalk niet, dan is de console het laatste kanaal dat overblijft.
    // Hier gooien zou elke aanroeper meeslepen — ook het vangnet dat juist fouten toont.
    if (!melding) {
        console.error('[bediening] melding niet te tonen:', tekst);

        return;
    }

    melding.hidden = false;
    melding.className = 'melding' + (soort ? ' melding--' + soort : '');
    meldingTekst.textContent = tekst;
    meldingLetOp.hidden = !uitleg;
    meldingLetOp.textContent = uitleg || '';
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

    // Uit /api/demo/omgeving: het adres /api/demo/personas hoort bij de personadienst en wordt door
    // deze module bewust met 404 beantwoord.
    personas: (body) =>
        body.personas.length + " persona's: " + body.personas.map((persona) => persona.label).join(', '),
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
    // Een overgeslagen stap is geen fout — het echte werk is gelukt — maar hij hoort ook niet groen
    // te zijn: er is iets niet gebeurd waar de bediener op rekende.
    if (body && body.gesimuleerd && body.gesimuleerd.overgeslagen) return 'let-op';

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

/* De naam waaronder een veld in een melding staat. Uit een attribuut en niet uit het `<label>`
 * ernaast: dat label leest als losse woorden om het veld heen ("Elke … seconden") en twee groepen
 * dragen allebei een veld dat "Aantal" heet, dus de melding zou niet zeggen wélk aantal. */
function veldnaam(veld) {
    return veld.dataset.veldnaam || veld.id;
}

function opsom(namen) {
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
        // TypeError zou uit `voerUit` vliegen zonder dat iets de knop verklaart.
        if (!veld || typeof veld.checkValidity !== 'function') {
            kwijt.push(id);

            return '';
        }

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
            // te weinig: bij "geslaagd, maar het antwoord had een onverwachte vorm" stond er anders
            // een groen vinkje naast een melding die twijfel uitsprak.
            zetUitkomst(knop, samengevat.soort === 'let-op' ? 'let-op' : 'gelukt');
            toonMelding(samengevat.tekst || 'Gelukt', samengevat.soort, uitkomst.ruw, letOp(uitkomst.body));
        } else {
            zetUitkomst(knop, 'mislukt');
            toonMelding(uitkomst.tekst, 'fout', uitkomst.ruw);
        }
    } catch (fout) {
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
    // Zonder deze klok wacht een uitlezing zolang de browser wil: een omgeving die de verbinding
    // openhoudt zonder te antwoorden — een trage cluster, een inlogpagina die ertussen komt — laat
    // alles wat op dit antwoord wacht onbeperkt hangen. Een tijdslimiet maakt daar een mislukking
    // van, en een mislukking is zichtbaar en opnieuw te proberen.
    const staak = new AbortController();
    const timer = setTimeout(() => staak.abort(), LEES_TIMEOUT_MS);

    // Buiten de try: breekt de timer af tijdens het lezen van de body, dan is de status het enige
    // dat de oorzaak nog aanwijst — een 401 van een inlogpagina ertussen, bijvoorbeeld.
    let status = null;

    try {
        const respons = await fetch(pad, { signal: staak.signal });

        status = respons.status;

        if (respons.ok) return await respons.json();

        console.error('toestand niet te lezen:', pad, status, await respons.text());
    } catch (fout) {
        // Op de fout zelf en niet op `signal.aborted`: die staat ook op true wanneer de timer net
        // ná een echte fout afgaat, en dan zou de diagnose de verkeerde oorzaak noemen.
        const afgebroken = fout && fout.name === 'AbortError';

        console.error('toestand niet te lezen:', pad, status, afgebroken ? 'geen antwoord binnen ' + LEES_TIMEOUT_MS + ' ms' : fout);
    } finally {
        clearTimeout(timer);
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
    if (bezig > 0) return;

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

    // Als laatste, en apart per lijst: elke keuzelijst hangt aan de vorm van het antwoord, en een
    // antwoord dat die vorm mist mag de knoppen hierboven niet meeslepen. Uit ditzelfde antwoord en niet van
    // /api/demo/personas: dat adres hoort bij de personadienst, en deze module beantwoordt het
    // bewust niet. Console onbereikbaar levert null op, waarop de keuzelijst zegt dat ze niet te
    // lezen was in plaats van dat er niets is ingericht.
    try {
        vulPersonas(omgeving ? omgeving.personas : null);
    } catch (fout) {
        console.error('[bediening] persona-keuzelijst niet te vullen', fout);
        vulPersonas(null);
    }

    try {
        vulBerichtPersonas(omgeving ? omgeving.berichtPersonas : null);
    } catch (fout) {
        console.error('[bediening] keuzelijst voor losse berichten niet te vullen', fout);
        vulBerichtPersonas(null);
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
    // Twee pogingen naast elkaar overschrijven elkaars uitkomst, en de laatste die terugkomt is niet
    // per se de meest actuele: dan meldt het paneel een storing die net verholpen is.
    if (inrichtLoopt) {
        if (metHand) toonInrichtingsfout('Er loopt al een poging om de omgeving te lezen.');

        return;
    }

    planInrichting(null);

    inrichtLoopt = true;

    // Meteen zichtbaar, want een uitlezing mag tot LEES_TIMEOUT_MS duren; zonder dit ziet een druk
    // op de knop er secondenlang uit alsof er niets gebeurde.
    zetInrichtenBezig(true);

    try {
        const gelukt = await pasOmgevingToe();

        if (!gelukt) {
            const wacht = INRICHT_WACHT[Math.min(inrichtPoging, INRICHT_WACHT.length - 1)];

            planInrichting(wacht);

            if (!metHand) inrichtPoging += 1;

            toonInrichtingsfout('Het paneel kon de omgeving niet lezen; knoppen die daarvan afhangen ' +
                'blijven uit. Volgende poging over ' + Math.round(wacht / 1000) + ' seconden.');

            return;
        }

        toonInrichting(false);

        // Alleen als er iets te melden vált: bij een gewone start heeft niemand om deze regel
        // gevraagd, en een melding die er altijd staat leest niemand meer.
        if (inrichtPoging > 0 || metHand) {
            toonMelding('De omgeving is gelezen; het paneel is compleet', 'goed', null);
        }

        inrichtPoging = 0;
    } catch (fout) {
        // Een fout in de bedrading zelf — een element dat de opmaak niet meer draagt. Die gaat niet
        // over van wachten, dus geen nieuwe poging; het blok blijft wél staan, want anders verdwijnt
        // met de enige melding ook de enige knop die er nog iets aan kan doen. `lees()` valt hier
        // niet onder: die vangt een onbereikbare console zelf af en geeft null.
        console.error('[bediening] omgeving niet toe te passen', fout);
        toonInrichtingsfout('Het paneel kon zichzelf niet inrichten: ' + fout);
    } finally {
        inrichtLoopt = false;

        zetInrichtenBezig(false);
    }
}

function toonInrichtingsfout(tekst) {
    if (inrichtingTekst) inrichtingTekst.textContent = tekst;

    toonInrichting(true);
}

/* Het blok met de knop staat in het paneel; het merkteken op de klap-knop hoort erbij, want een
 * ingeklapt paneel toont dit blok niet. */
function toonInrichting(zichtbaar) {
    if (!inrichting) {
        meldOpmaakfout('het blok voor een mislukte inrichting');

        return;
    }

    inrichting.hidden = !zichtbaar;

    markeerKlap(zichtbaar);
}

/* De knop uit terwijl zijn eigen poging loopt: hij zou anders een tweede poging naast de eerste
 * starten, en `richtIn` heeft daar niets aan toe te voegen behalve een afwijzing. */
function zetInrichtenBezig(bezigMetInrichten) {
    if (!inrichtingKnop) return;

    inrichtingKnop.disabled = bezigMetInrichten;

    if (bezigMetInrichten && inrichtingTekst) inrichtingTekst.textContent = 'Bezig de omgeving te lezen…';
}

/* Eén timer voor de volgende poging, en die wordt altijd eerst gewist. Zonder dat wissen laat een
 * druk op de knop terwijl er al een poging gepland stond twee timers achter: vanaf dan verdubbelt
 * het aantal pogingen bij elke ronde. `null` plant niets en wist alleen. */
function planInrichting(wacht) {
    clearTimeout(inrichtTimer);

    inrichtTimer = wacht === null ? null : setTimeout(richtIn, wacht);
}

/* De ontdubbeling loopt op een BSN, dus alleen persona's met een BSN kunnen hem spelen. Een vrij
 * tekstveld zou een BSN vragen die verderop in dezelfde pagina al als keuzelijst bestaat. */
function vulPersonas(personas) {
    const keuze = document.getElementById('ontdubbelPersona');
    const knop = document.querySelector('button[data-samenvatting="ontdubbeling"]');

    if (!Array.isArray(personas)) {
        meldOnbruikbareLijst('personas', personas, keuze, knop, 'ontdubbelPersona');

        return;
    }

    vulKeuze(
        keuze,
        knop,
        personas
            .filter((persona) => persona.ontvanger.startsWith('BSN:'))
            .map((persona) => ({ waarde: persona.ontvanger.slice('BSN:'.length), label: persona.label })),
        'geen persona met een BSN ingericht',
    );
}

/* De persona's waarvoor de console kán aanleveren; de uitvraag levert die deelverzameling apart.
 * De waarde is de persona-id, want die gaat als queryparameter mee. */
function vulBerichtPersonas(personas) {
    const keuze = document.getElementById('berichtPersona');
    const knop = document.getElementById('berichtKnop');

    if (!Array.isArray(personas)) {
        meldOnbruikbareLijst('berichtPersonas', personas, keuze, knop, 'berichtPersona');

        return;
    }

    vulKeuze(
        keuze,
        knop,
        personas.map((persona) => ({ waarde: persona.id, label: persona.label })),
        'geen persona met een magazijn ingericht',
    );
}

/* Niet op `=== null`: `lees()` geeft `null` voor élke onbereikbare of onleesbare console, dus wat
 * hier overblijft is een antwoord dát binnenkwam waarin het veld ontbreekt of van vorm veranderd is
 * — een console die dit veld nog niet kent, of een proxy die het herschrijft. Dat is een andere
 * oorzaak dan "de console was even weg", en de keuzelijst kan dat verschil niet tonen. Altijd
 * loggen, ook bij `null`: anders is het geval "de console antwoordde met null" van de twee andere
 * niet te onderscheiden. */
function meldOnbruikbareLijst(veld, waarde, keuze, knop, keuzeId) {
    console.error('[bediening] ' + veld + ' is niet als lijst binnengekomen', waarde);

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
 * wijst in plaats van naar de mislukte uitlezing.
 *
 * Ontbreekt een element, dan is de opmaak veranderd zonder dit script. Dat gaat naar de
 * meldingsbalk en niet alleen naar de console: wie een demo geeft heeft geen devtools open. */
function meldLijstOnbekend(keuze, knop, keuzeId) {
    if (knop) zetWachtOpLijst(knop, true);

    // De id erbij: er zijn twee keuzelijsten, en "een keuzelijst ontbreekt" laat de bediener niet
    // zien wélke bediening dood is. Allebei melden als allebei de elementen weg zijn, anders blijft
    // de helft van de storing onbenoemd.
    if (!knop) meldOpmaakfout('de knop bij keuzelijst ' + (keuze ? keuze.id : keuzeId));

    if (!keuze) {
        meldOpmaakfout('de keuzelijst ' + keuzeId);

        return;
    }

    const onbekend = document.createElement('option');

    onbekend.value = '';
    onbekend.textContent = 'persona-lijst niet op te halen';

    keuze.replaceChildren(onbekend);
    keuze.disabled = true;
}

/* Het inrichten herhaalt zichzelf, dus een ontbrekend element komt elke ronde langs. Loggen doen we
 * dan wel steeds — dat is diagnostiek — maar de meldingsbalk houdt de uitkomst van de laatste actie
 * vast en mag daar niet elke 30 seconden overheen geschreven worden. */
const gemeldeOpmaakfouten = new Set();

function meldOpmaakfout(wat) {
    console.error('[bediening] ' + wat + ' ontbreekt in de opmaak');

    if (gemeldeOpmaakfouten.has(wat)) return;

    gemeldeOpmaakfouten.add(wat);
    toonMelding('Het paneel mist ' + wat + ' in zijn opmaak; die bediening werkt niet', 'fout', null);
}

function vulKeuze(keuze, knop, opties, leegTekst) {
    // Dezelfde toets als in meldLijstOnbekend: zonder deze zou een ontbrekend element hier een
    // TypeError geven, die de aanroeper opvangt en als "lijst niet op te halen" toont — terwijl de
    // lijst gewoon binnenkwam. Precies de verkeerde diagnose, in de andere richting.
    if (!keuze || !knop) {
        meldOpmaakfout(keuze ? 'de knop bij keuzelijst ' + keuze.id : 'een keuzelijst');

        return;
    }

    keuze.replaceChildren();
    keuze.disabled = false;
    zetWachtOpLijst(knop, false);

    if (!opties.length) {
        const leeg = document.createElement('option');

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

// ---------------------------------------------------------------- bedrading

const LOSSE_ACTIES = {
    klap: klap,
    'ververs-box': verversBox,
    'ververs-toestand': verversToestand,
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
        // op die wordt aangeroepen in plaats van gemeld. Een onbekende naam zou hier een TypeError
        // geven die uit de listener vliegt — de knop doet dan niets en niets zegt waarom.
        if (!Object.hasOwn(LOSSE_ACTIES, knop.dataset.actie)) {
            meldOpmaakfout('de actie ' + knop.dataset.actie);

            return;
        }

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

/* Eén vangnet voor wat buiten een eigen try/catch omvalt: een throw uit de click-listener, en een
 * afgewezen promise uit de aanroepen die fire-and-forget zijn. Zonder dit blijft zo'n fout in de
 * browserconsole hangen — en wie een demo geeft heeft geen devtools open, dus die ziet alleen een
 * knop die niets doet. */
window.addEventListener('error', (gebeurtenis) => {
    toonMelding('Onverwachte fout in het paneel: ' + gebeurtenis.message, 'fout', null);
});

window.addEventListener('unhandledrejection', (gebeurtenis) => {
    toonMelding('Onverwachte fout in het paneel: ' + gebeurtenis.reason, 'fout', null);
});

herstelStand();

// Fire-and-forget, en dat mag: `richtIn` handelt zowel een onbereikbare console als een fout in de
// eigen bedrading zelf af, en plant waar dat zin heeft een nieuwe poging. Wat er alsnog uit komt,
// vangt de unhandledrejection-listener hierboven.
richtIn(false);

verversToestand();

// Alleen pollen terwijl er iemand kijkt: een demo-console blijft dagen in een tab openstaan.
setInterval(() => {
    if (!document.hidden) verversToestand();
}, POLL_MS);
