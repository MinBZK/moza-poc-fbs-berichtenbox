/* Herstel van de inlogsessie. Op een gedeelde omgeving staat dit component achter een
 * oauth2-proxy: die beantwoordt een aanvraag zónder sessie met HTTP 403 en de inlogpagina als
 * HTML — geen 302. Een fetch() ziet dus een gewone mislukking met een body die geen JSON is, en
 * elke pagina hier zou dat als een storing in de keten tonen terwijl er alleen opnieuw ingelogd
 * moet worden.
 *
 * Gedeeld door het paneel en de berichtenbox-pagina, want beide worden door dit component
 * uitgeserveerd en vallen dus achter dezelfde muur. */

'use strict';

/* De enige eenduidige controle die de proxy biedt: 202 met sessie, 401 zonder. Nodig naast de 403,
 * want die kan ook van de applicatie zelf komen — en dan zou een herstelpoging de bediener midden
 * in een demo wegleiden van een pagina die het prima doet. Lokaal, zonder muur, antwoordt de
 * applicatie hier met 404 en blijft alles bij het oude. */
const MUUR_CONTROLE_PAD = '/oauth2/auth';

/* Waar de proxy zijn eigen aanmelding start. `rd` brengt je terug op de pagina waar je stond; de
 * proxy accepteert daarvoor alleen een pad op dezelfde host. Rechtstreeks naar /start en niet
 * simpelweg de pagina herladen: herladen levert eerst de inlogkaart met een knop op, terwijl deze
 * route bij een geldige SSO-sessie vanzelf doorloopt en terugkomt. */
const MUUR_START_PAD = '/oauth2/start';

/* Hoe lang een volgende herstelpoging wacht. Zonder deze demping stuurt een muur die blijft
 * weigeren de bediener elke poll opnieuw het inlogpad in, en blijft de pagina heen en weer
 * springen zonder ooit iets te tonen. */
const MUUR_DEMPING_MS = 30000;

/* In sessionStorage: de demping moet de navigatie naar de proxy en terug overleven — een variabele
 * in het geheugen is bij terugkomst juist weg, precies wanneer de lus zou beginnen. */
const MUUR_SLEUTEL = 'fbs-demo-inlogmuur:laatste-poging';

/* Of de navigatie naar het inlogpad al is ingezet. De poll en een druk op een knop kunnen elkaar
 * overlappen, en twee navigaties tegelijk laten de browser de eerste afbreken. */
let muurHerstelLoopt = false;

/* Of een mislukte aanvraag van de muur kwam en niet van de applicatie. Alleen de 403 van de muur
 * hoort tot een herstelpoging te leiden. */
async function inlogsessieVerlopen(status) {
    if (status !== 403) return false;

    try {
        const controle = await fetch(MUUR_CONTROLE_PAD, { cache: 'no-store' });

        return controle.status === 401;
    } catch (fout) {
        // Netwerkfout op de controle zegt niets over de sessie; laat de aanroeper zijn gewone
        // mislukking tonen in plaats van hier een herstelpoging te verzinnen.
        console.error('[inlogmuur] controle op de inlogsessie mislukt', fout);

        return false;
    }
}

/* Zet het opnieuw inloggen in gang. Geeft terug óf dat gelukt is, zodat de aanroeper weet welke
 * melding hoort bij wat er nu gebeurt. */
function herstelInlogsessie() {
    if (muurHerstelLoopt) return true;

    // In een frame leidt dit nergens heen: de proxy en Keycloak weigeren zich te laten insluiten,
    // dus de bediener zou een leeg vak overhouden zonder te weten waarom.
    if (window.top !== window.self) return false;

    if (!dempingVerstreken()) return false;

    muurHerstelLoopt = true;
    onthoudHerstelpoging();

    window.location.assign(MUUR_START_PAD + '?rd=' + encodeURIComponent(location.pathname + location.search));

    return true;
}

/* De melding die hoort bij een verlopen sessie, en tegelijk de herstelpoging zelf. Eén ingang voor
 * beide, zodat geen enkele aanroeper de melding zonder de poging kan tonen. */
function muurMelding() {
    return herstelInlogsessie()
        ? 'Je was uitgelogd bij de omgeving; je wordt opnieuw ingelogd…'
        : 'Je bent uitgelogd bij de omgeving. Ververs de pagina om opnieuw in te loggen.';
}

/* Storage kan gooien wanneer site-data geblokkeerd is. Dan liever herstellen zonder demping dan
 * helemaal niet: de bediener heeft meer aan een poging dan aan een dood paneel. */
function dempingVerstreken() {
    try {
        const vorige = Number(window.sessionStorage.getItem(MUUR_SLEUTEL));

        if (!vorige) return true;

        return Date.now() - vorige > MUUR_DEMPING_MS;
    } catch (fout) {
        console.error('[inlogmuur] demping niet te lezen', fout);

        return true;
    }
}

function onthoudHerstelpoging() {
    try {
        window.sessionStorage.setItem(MUUR_SLEUTEL, String(Date.now()));
    } catch (fout) {
        console.error('[inlogmuur] demping niet te bewaren', fout);
    }
}
