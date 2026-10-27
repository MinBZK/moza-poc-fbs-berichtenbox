// Ophalen en samenvoegen met de code van de Berichten-app tegen een draaiende magazijn-simulator.
// De tests van de simulator starten dit script. Het speelt ook de App Manager: met de sleutel die de
// simulator vertrouwt, tekent het een credential op de niet-exporteerbare sleutel van de app.
// Schrijft één regel JSON naar stdout.
//
//   node tegen-simulator.mjs <magazijnen-json> <app-manager-pkcs8-base64> <app-manager-oin> <kid> <ontvanger> <nu-epoch>
import { CompactSign } from '../../main/resources/META-INF/resources/vendor/jose-6.2.13/index.js';
import { publiekeJwk } from '../../main/resources/META-INF/resources/js/activeren.js';
import { haalAllesOp } from '../../main/resources/META-INF/resources/js/ophalen.js';
import { maakSleutelpaar } from '../../main/resources/META-INF/resources/js/sleutelkluis.js';

const [magazijnenJson, appManagerSleutel, appManagerOin, kid, ontvanger, nuTekst] = process.argv.slice(2);
const nu = Number(nuTekst);
const sleutelpaar = await maakSleutelpaar();

const appManager = await crypto.subtle.importKey(
  'pkcs8', Buffer.from(appManagerSleutel, 'base64'), { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign'],
);
const credential = await new CompactSign(new TextEncoder().encode(JSON.stringify({
  cnf: { jwk: await publiekeJwk(sleutelpaar.publicKey) },
  scope: 'nl.rijksoverheid.moz.fbs.berichten',
  iss: appManagerOin,
  iat: nu,
  nbf: nu,
  exp: nu + 3600,
  ontvanger,
}))).setProtectedHeader({ alg: 'ES256', typ: 'jwt', kid }).sign(appManager);

const { berichten, mislukt } = await haalAllesOp(JSON.parse(magazijnenJson), { sleutelpaar, credential });

console.log(JSON.stringify({
  berichten: berichten.map(({ onderwerp, magazijn, publicatietijdstip }) => ({ onderwerp, magazijn, publicatietijdstip })),
  mislukt: mislukt.map(({ oin, reden }) => ({ oin, reden })),
}));
