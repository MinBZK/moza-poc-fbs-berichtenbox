// De sleutels en tokens van de app in IndexedDB. Een CryptoKey gaat als object de database in:
// script op deze origin kan ermee tekenen of ontsleutelen, maar de private key niet uitlezen.
const DATABASE = 'berichten-app';
const OPSLAG = 'kluis';

/**
 * De root app key: ECDSA op P-256 voor ES256, met `extractable: false`, zodat `exportKey` op de
 * private key weigert. De public key blijft volgens WebCrypto altijd exporteerbaar.
 */
export function maakSleutelpaar() {
  return crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign', 'verify']);
}

/** Het sleutelpaar van deze browser; bij het eerste gebruik gemaakt. */
export async function sleutelpaar() {
  const bestaand = await haal('sleutelpaar');

  if (bestaand) return bestaand;

  const nieuw = await maakSleutelpaar();

  await bewaar('sleutelpaar', nieuw);

  return nieuw;
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

/** Sleutel, tokens en credential weg: de browser moet daarna opnieuw activeren. */
export function wis() {
  return transactie('readwrite', (opslag) => opslag.clear());
}

function open() {
  return new Promise((gelukt, mislukt) => {
    const verzoek = indexedDB.open(DATABASE, 1);

    verzoek.onupgradeneeded = () => verzoek.result.createObjectStore(OPSLAG);
    verzoek.onsuccess = () => gelukt(verzoek.result);
    verzoek.onerror = () => mislukt(verzoek.error);
  });
}

async function transactie(modus, actie) {
  const database = await open();

  try {
    return await new Promise((gelukt, mislukt) => {
      const tx = database.transaction(OPSLAG, modus);
      const verzoek = actie(tx.objectStore(OPSLAG));

      tx.oncomplete = () => gelukt(verzoek.result);
      tx.onerror = () => mislukt(tx.error);
      tx.onabort = () => mislukt(tx.error);
    });
  } finally {
    database.close();
  }
}
