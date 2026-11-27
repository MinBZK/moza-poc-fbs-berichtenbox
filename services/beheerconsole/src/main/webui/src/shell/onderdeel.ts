import type { ReactNode } from "react";

/**
 * Eén scherm van een onderdeel. Het pad is relatief aan het basispad van de app.
 */
export interface Scherm {
  pad: string;
  menutekst: string;
  icoon?: string;
  inhoud: ReactNode;
}

/**
 * Een onderdeel levert zijn eigen schermen en praat alleen met zijn eigen API. De shell kent
 * geen inhoud: een tweede onderdeel (bijvoorbeeld de schermen van het NMC-portaal) komt erbij
 * door het in de lijst van de app op te nemen.
 */
export interface Onderdeel {
  naam: string;
  schermen: Scherm[];
}
