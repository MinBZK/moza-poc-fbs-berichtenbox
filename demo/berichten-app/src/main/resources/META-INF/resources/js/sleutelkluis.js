// De sleutels en tokens van de app in IndexedDB. Een CryptoKey gaat als object de database in:
// script op deze origin kan ermee tekenen of ontsleutelen, maar de private key niet uitlezen.
const DATABASE = 'berichten-app';
const OPSLAG = 'kluis';
const SLEUTELPAAR = 'sleutelpaar';

let lopend = null;

/** Een fout van de opslag in de browser, los van de App Manager. */
export class KluisFout extends Error {}

/**
 * De root app key: ECDSA op P-256 voor ES256, met `extractable: false`, zodat `exportKey` op de
 * private key weigert. De public key blijft volgens WebCrypto altijd exporteerbaar.
 */
export function maakSleutelpaar() {
  return crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign', 'verify']);
}

/**
 * Het sleutelpaar van deze browser; bij het eerste gebruik gemaakt. Twee aanroepen tegelijk krijgen
 * hetzelfde paar: anders kan de App Manager de identiteit aan een sleutel binden die de kluis niet
 * bewaart, en weigert hij later elk vernieuwen. Alleen zolang de aanroep loopt: daarna leest elke
 * aanroep de kluis opnieuw, want een ander tabblad kan hem intussen gewist hebben.
 */
export function sleutelpaar() {
  lopend ??= haalOfMaak().finally(() => {
    lopend = null;
  });

  return lopend;
}

async function haalOfMaak() {
  const bestaand = await haal(SLEUTELPAAR);

  if (bestaand) return bestaand;

  const nieuw = await maakSleutelpaar();

  try {
    await transactie('readwrite', (opslag) => opslag.add(nieuw, SLEUTELPAAR));

    return nieuw;
  } catch (fout) {
    // Een ander tabblad was eerder: diens sleutel geldt.
    if (fout?.name === 'ConstraintError') return haal(SLEUTELPAAR);

    throw fout;
  }
}

export function haal(naam) {
  return transactie('readonly', (opslag) => opslag.get(naam));
}

export function bewaar(naam, waarde) {
  return transactie('readwrite', (opslag) => opslag.put(waarde, naam));
}

export function verwijder(naam) {
  return transactie('readwrite', (opslag) => opslag.delete(naam));
}

/**
 * Een nieuw `registration_token`, en het credential van de vorige registratie weg, in één
 * transactie: anders kan het bewijs van een andere persona naast de nieuwe registratie blijven staan.
 */
export function vervangRegistratie(token) {
  return transactie('readwrite', (opslag) => {
    opslag.delete('credential');

    return opslag.put(token, 'registratie');
  });
}

/** Sleutel, tokens en credential weg: de browser moet daarna opnieuw activeren. */
export function wis() {
  return transactie('readwrite', (opslag) => opslag.clear());
}

function open() {
  return new Promise((gelukt, mislukt) => {
    const verzoek = indexedDB.open(DATABASE, 1);

    verzoek.onupgradeneeded = () => verzoek.result.createObjectStore(OPSLAG);
    verzoek.onsuccess = () => gelukt(verzoek.result);
    verzoek.onerror = () => mislukt(new KluisFout('de opslag is niet te openen', { cause: verzoek.error }));
    verzoek.onblocked = () => mislukt(new KluisFout('de opslag is bezet door een ander tabblad met een oudere versie'));
  });
}

async function transactie(modus, actie) {
  const database = await open();

  try {
    return await new Promise((gelukt, mislukt) => {
      const tx = database.transaction(OPSLAG, modus);
      const verzoek = actie(tx.objectStore(OPSLAG));

      tx.oncomplete = () => gelukt(verzoek.result);
      // Pas bij abort staat de fout op de transactie; in onerror is tx.error nog null. Een
      // ConstraintError gaat ongewijzigd door: daaraan herkent haalOfMaak een ander tabblad.
      tx.onabort = () => {
        const oorzaak = tx.error;

        mislukt(oorzaak?.name === 'ConstraintError' ? oorzaak : new KluisFout('de opslag brak de transactie af', { cause: oorzaak }));
      };
    });
  } finally {
    database.close();
  }
}
