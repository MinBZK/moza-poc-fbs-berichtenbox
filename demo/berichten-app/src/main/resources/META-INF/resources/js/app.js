// De pagina: activeren, het credential vernieuwen en alles vergeten. Tekst uit tokens en
// credentials gaat alleen via textContent de pagina in.
import * as kluis from './sleutelkluis.js';
import { haalCredential, leesCredential, leesRegistratie, maakSessie, registratieAdres } from './activeren.js';

// Het session_token van het activeren overleeft zo de navigatie naar de App Manager, maar niet het
// sluiten van het tabblad. De AES-sleutel staat als niet-exporteerbare CryptoKey in de kluis.
const ACTIVATIE_SESSIE = 'activatie-sessie';
const ACTIVATIE_SLEUTEL = 'activatie-aes';

const element = (id) => document.getElementById(id);

async function laadConfig() {
  const antwoord = await fetch('config.json');

  if (!antwoord.ok) throw new Error(`De configuratie is niet te laden (HTTP ${antwoord.status}).`);

  return antwoord.json();
}

/** Terug van de App Manager: het token uit het fragment halen en er een credential mee ophalen. */
async function rondActiverenAf(appManager) {
  if (!location.hash.includes('registration_token=')) return;

  const fragment = location.hash;

  // Het token hoort niet in de geschiedenis van de browser te blijven staan.
  history.replaceState(null, '', location.pathname + location.search);

  const aesSleutel = await kluis.haal(ACTIVATIE_SLEUTEL);
  const sessieToken = sessionStorage.getItem(ACTIVATIE_SESSIE);

  await kluis.verwijder(ACTIVATIE_SLEUTEL);
  sessionStorage.removeItem(ACTIVATIE_SESSIE);

  if (!aesSleutel || !sessieToken) throw new Error('Het activeren is in een ander tabblad begonnen of verlopen; begin opnieuw.');

  const registratieToken = await leesRegistratie(fragment, aesSleutel, appManager.url);

  // Eerst het token bewaren: mislukt het ophalen hierna, dan haalt "vernieuwen" het later alsnog.
  await kluis.bewaar('registratie', registratieToken);
  await kluis.bewaar('credential', await haalCredential({ basis: appManager.url, sessieToken, aesSleutel, registratieToken }));
}

async function activeer(appManager) {
  const sleutelpaar = await kluis.sleutelpaar();
  const { sessieToken, aesSleutel } = await maakSessie({ basis: appManager.url, oin: appManager.oin, sleutelpaar });

  await kluis.bewaar(ACTIVATIE_SLEUTEL, aesSleutel);
  sessionStorage.setItem(ACTIVATIE_SESSIE, sessieToken);
  location.assign(registratieAdres(appManager.url, sessieToken));
}

/** Een vers credential zonder opnieuw te kiezen: een nieuwe sessie, hetzelfde registration_token. */
async function vernieuw(appManager) {
  const registratieToken = await kluis.haal('registratie');

  if (!registratieToken) throw new Error('Deze browser is nog niet geactiveerd.');

  const sleutelpaar = await kluis.sleutelpaar();
  const { sessieToken, aesSleutel } = await maakSessie({ basis: appManager.url, oin: appManager.oin, sleutelpaar });

  await kluis.bewaar('credential', await haalCredential({ basis: appManager.url, sessieToken, aesSleutel, registratieToken }));
}

async function vergeet() {
  await kluis.wis();
  sessionStorage.removeItem(ACTIVATIE_SESSIE);
}

async function toon() {
  const credential = await kluis.haal('credential');

  element('vernieuwen').disabled = !(await kluis.haal('registratie'));

  if (!credential) {
    element('status').textContent = 'Deze browser is nog niet geactiveerd.';

    return;
  }

  const { ontvanger, verlooptOp } = leesCredential(credential);
  const geldig = verlooptOp > new Date() ? `geldig tot ${verlooptOp.toLocaleString('nl-NL')}` : 'verlopen';

  element('status').textContent = `Geactiveerd voor ${ontvanger}. Het credential is ${geldig}.`;
}

function meld(fout) {
  element('fout').textContent = fout instanceof Error ? fout.message : String(fout);
}

function bij(id, actie) {
  element(id).addEventListener('click', () => {
    element('fout').textContent = '';
    actie().then(toon).catch(meld);
  });
}

async function start() {
  const { appManager } = await laadConfig();

  bij('activeren', () => activeer(appManager));
  bij('vernieuwen', () => vernieuw(appManager));
  bij('vergeten', vergeet);

  try {
    await rondActiverenAf(appManager);
  } finally {
    await toon();
  }
}

start().catch(meld);
