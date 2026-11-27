// De typen volgen het schema Stelseloverzicht in beheerconsole-api.yaml.
export interface Stelseloverzicht {
  verificatie: { status: "GELDIG" | "ONGELDIG" | "NIET_GETOETST"; reden?: string };
  document?: { uitgever: string; omgeving: string; versie: string; uitgegevenOp: string; geldigTot: string };
  ondertekening?: { kid: string; onderwerp: string; certificaatGeldigTot: string };
  organisaties: { oin: string; naam: string; magazijnUrl: string }[];
}

export async function haalStelseloverzicht(): Promise<Stelseloverzicht> {
  const antwoord = await fetch(import.meta.env.BASE_URL + "api/v1/stelsel", { headers: { Accept: "application/json" } });

  if (!antwoord.ok) throw new Error(`het stelselregister gaf geen document (${antwoord.status})`);

  return antwoord.json();
}
