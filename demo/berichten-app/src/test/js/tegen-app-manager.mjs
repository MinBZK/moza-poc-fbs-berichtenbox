// Het hele activeren met de code van de Berichten-app tegen een draaiende App Manager. De tests van
// de App Manager starten dit script; wat de browser zelf doet (de doorverwijzingen volgen, de cookie
// meesturen, de persona kiezen), doet het script na. Schrijft één regel JSON naar stdout.
//
//   node tegen-app-manager.mjs <basis> <issuer> <oin> <persona>
import { decodeJwt } from '../../main/resources/META-INF/resources/vendor/jose-6.2.13/index.js';
import {
  haalCredential, leesRegistratie, maakSessie, publiekeJwk, registratieAdres,
} from '../../main/resources/META-INF/resources/js/activeren.js';
import { maakSleutelpaar } from '../../main/resources/META-INF/resources/js/sleutelkluis.js';

const [basis, issuer, oin, persona] = process.argv.slice(2);
const sleutelpaar = await maakSleutelpaar();
const activatie = await maakSessie({ basis, oin, sleutelpaar });

const omwisseling = await fetch(registratieAdres(basis, activatie.sessieToken), { redirect: 'manual' });
const cookie = omwisseling.headers.get('set-cookie')?.split(';')[0];

if (omwisseling.status !== 303 || !cookie) throw new Error(`register_app gaf ${omwisseling.status} zonder koppelcookie`);

const keuze = await fetch(new URL('register_app/kies', basis), {
  method: 'POST',
  redirect: 'manual',
  headers: { Cookie: cookie, 'Content-Type': 'application/x-www-form-urlencoded' },
  body: new URLSearchParams({ persona }).toString(),
});

if (keuze.status !== 303) throw new Error(`register_app/kies gaf ${keuze.status} in plaats van 303`);

const terug = new URL(keuze.headers.get('location'));
const registratieToken = await leesRegistratie(terug.hash, activatie.aesSleutel, issuer);
const credential = await haalCredential({ basis, ...activatie, registratieToken });

// Later: een nieuwe sessie op dezelfde sleutel, hetzelfde token, een vers credential.
const later = await maakSessie({ basis, oin, sleutelpaar });
const vers = await haalCredential({ basis, ...later, registratieToken });

console.log(JSON.stringify({
  terug: terug.origin + terug.pathname,
  credential,
  vers,
  cnf: decodeJwt(credential).cnf.jwk,
  jwk: await publiekeJwk(sleutelpaar.publicKey),
}));
