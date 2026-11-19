// De sleutels en tokens van de app in IndexedDB. Een CryptoKey gaat als object de database in:
// script op deze origin kan ermee ondertekenen of ontsleutelen, maar de private key niet uitlezen.
const DATABASE = 'berichten-app';
const OPSLAG = 'kluis';
const SLEUTELPAAR = 'sleutelpaar';

/** Het lopende activeren: sessietoken en AES-sleutel, tot de app terug is van de App Manager. */
export const ACTIVATIE = 'activatie';
export const REGISTRATIE = 'registratie';
export const CREDENTIAL = 'credential';

let lopend = null;

/** Een fout van de opslag in de browser, los van de App Manager. */
export class KluisFout extends Error {
  name = 'KluisFout';
}

/**
 * De root app key: ECDSA op P-256 voor ES256, met `extractable: false`, zodat `exportKey` op de
 * private key weigert. De public key blijft volgens WebCrypto altijd exporteerbaar.
 */
export function maakSleutelpaar() {
  return crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign', 'verify']);
}

/**
 * Het sleutelpaar van deze browser; bij het eerste gebruik gemaakt. Twee aanroepen tegelijk delen
 * één poging, zodat er niet twee paren gemaakt worden. Dat de bewaarde sleutel wint, ook tegen een
 * ander tabblad, regelt `add` met de terugval op `ConstraintError` in `haalOfMaak`. Alleen zolang de
 * aanroep loopt: daarna leest elke aanroep de kluis opnieuw, want een ander tabblad kan hem
 * intussen gewist hebben.
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
    // `add` en niet `put`: een paar dat er al staat, mag nooit overschreven worden.
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
 * Een nieuw `registration_token`, in één transactie met het wissen van het credential van de vorige
 * registratie en van het afgeronde activeren: anders kan het bewijs van een andere persona naast de
 * nieuwe registratie blijven staan, of blijft het activeren half staan.
 */
export function vervangRegistratie(token) {
  return transactie('readwrite', (opslag) => {
    opslag.delete(CREDENTIAL);
    opslag.delete(ACTIVATIE);

    return opslag.put(token, REGISTRATIE);
  });
}

/**
 * Bewaart een credential, maar alleen als [token] nog de registratie is: een ander tabblad kan
 * intussen opnieuw geactiveerd hebben, en dan hoort dit credential er niet meer bij. Geeft terug of
 * het bewaard is.
 */
export function bewaarCredential(token, credential) {
  return alsRegistratie(token, (opslag) => opslag.put(credential, CREDENTIAL));
}

/**
 * Wist registratie en credential, maar alleen als [token] nog de registratie is; anders zou een
 * afgewezen oud token de nieuwe activering van een ander tabblad wissen. Geeft terug of er gewist is.
 */
export function vergeetRegistratie(token) {
  return alsRegistratie(token, (opslag) => {
    opslag.delete(REGISTRATIE);
    opslag.delete(CREDENTIAL);
  });
}

function alsRegistratie(token, actie) {
  return transactie('readwrite', (opslag) => {
    const uitkomst = { result: false };
    const huidig = opslag.get(REGISTRATIE);

    huidig.onsuccess = () => {
      if (huidig.result !== token) return;

      actie(opslag);
      uitkomst.result = true;
    };

    return uitkomst;
  });
}

/** Sleutel, tokens en credential weg: de browser moet daarna opnieuw activeren. */
export function wis() {
  return transactie('readwrite', (opslag) => opslag.clear());
}

function open() {
  return new Promise((gelukt, mislukt) => {
    let verzoek;
    let afgewezen = false;

    try {
      verzoek = indexedDB.open(DATABASE, 1);
    } catch (fout) {
      // Een SecurityError, bijvoorbeeld als de browser opslag voor deze origin verbiedt.
      mislukt(new KluisFout('de opslag is niet te openen', { cause: fout }));

      return;
    }

    verzoek.onupgradeneeded = () => verzoek.result.createObjectStore(OPSLAG);
    verzoek.onsuccess = () => {
      // Na een onblocked kan de opening later toch lukken; die verbinding gebruikt niemand meer.
      if (afgewezen) {
        verzoek.result.close();

        return;
      }

      gelukt(verzoek.result);
    };
    verzoek.onerror = () => mislukt(new KluisFout('de opslag is niet te openen', { cause: verzoek.error }));
    verzoek.onblocked = () => {
      afgewezen = true;
      mislukt(new KluisFout('de opslag is bezet door een ander tabblad met een oudere versie'));
    };
  });
}

async function transactie(modus, actie) {
  const database = await open();

  try {
    return await new Promise((gelukt, mislukt) => {
      let tx;
      let verzoek;

      try {
        tx = database.transaction(OPSLAG, modus);
        verzoek = actie(tx.objectStore(OPSLAG));
      } catch (fout) {
        mislukt(new KluisFout('de opslag weigerde de bewerking', { cause: fout }));

        return;
      }

      tx.oncomplete = () => gelukt(verzoek?.result);
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
