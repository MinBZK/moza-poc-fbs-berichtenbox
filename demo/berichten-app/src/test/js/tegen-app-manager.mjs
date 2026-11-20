// Het hele activeren met de code van de Berichten-app tegen een draaiende App Manager. De tests van
// de App Manager starten dit script; wat de browser zelf doet (de doorverwijzingen volgen, de cookie
// en de Origin van de keuzepagina meesturen, de persona kiezen), doet het script na. Schrijft één
// regel JSON naar stdout.
//
//   node --import ./jose-uit-npm.mjs tegen-app-manager.mjs <basis> <issuer> <oin> <persona>
import {
  haalCredential, leesRegistratie, maakSessie, publiekeJwk, registratieAdres,
} from '../../main/resources/META-INF/resources/js/activeren.js';
import * as kluis from '../../main/resources/META-INF/resources/js/sleutelkluis.js';
import { Melding, vernieuw } from '../../main/resources/META-INF/resources/js/verloop.js';
import { IDBFactory } from 'fake-indexeddb';

const [basis, issuer, oin, persona] = process.argv.slice(2);
const sleutelpaar = await kluis.maakSleutelpaar();
const activatie = await maakSessie({ basis, oin, sleutelpaar });

const omwisseling = await fetch(registratieAdres(basis, activatie.sessieToken), { redirect: 'manual' });
const cookie = omwisseling.headers.get('set-cookie')?.split(';')[0];

if (omwisseling.status !== 303 || !cookie) throw new Error(`register_app gaf ${omwisseling.status} zonder koppelcookie`);

const keuze = await fetch(new URL('register_app/kies', basis), {
  method: 'POST',
  redirect: 'manual',
  // De keuzepagina staat op de App Manager zelf; een browser zet die origin in de Origin-header,
  // omdat de pagina zijn referrer-policy op same-origin zet (onder no-referrer zou het `null` zijn).
  headers: { Cookie: cookie, Origin: new URL(issuer).origin, 'Content-Type': 'application/x-www-form-urlencoded' },
  body: new URLSearchParams({ persona }).toString(),
});

if (keuze.status !== 303) throw new Error(`register_app/kies gaf ${keuze.status} in plaats van 303`);

const terug = new URL(keuze.headers.get('location'));
const registratieToken = await leesRegistratie(terug.hash, activatie.aesSleutel, issuer);
const credential = await haalCredential({ basis, ...activatie, registratieToken });

// Later: een nieuwe sessie op dezelfde sleutel, hetzelfde token, een vers credential.
const later = await maakSessie({ basis, oin, sleutelpaar });
const vers = await haalCredential({ basis, ...later, registratieToken });

// Een registratie die de App Manager niet kent: vernieuwen moet hem uit de kluis halen. Dat leunt op
// de 401 van de App Manager; zo toetst de keten het, niet alleen elk van beide kanten.
globalThis.indexedDB = new IDBFactory();
await kluis.bewaar(kluis.REGISTRATIE, 'onbekend');

const afgewezen = await vernieuw({ appManager: { url: basis, oin }, kluis }).then(() => null, (fout) => fout);

console.log(JSON.stringify({
  onbekendVergeten: afgewezen instanceof Melding && await kluis.haal(kluis.REGISTRATIE) === undefined,
  terug: terug.origin + terug.pathname,
  credential,
  vers,
  jwk: await publiekeJwk(sleutelpaar.publicKey),
}));
