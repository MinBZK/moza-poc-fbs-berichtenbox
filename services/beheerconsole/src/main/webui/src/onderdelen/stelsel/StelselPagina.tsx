import "@nldd/design-system/simple-section";
import "@nldd/design-system/title";
import "@nldd/design-system/banner";
import "@nldd/design-system/table";
import "@nldd/design-system/text-cell";
import "@nldd/design-system/rich-text";
import { useEffect, useState } from "react";
import { haalStelseloverzicht, type Stelseloverzicht } from "./api";

const VERIFICATIE = {
  GELDIG: { variant: "success", tekst: "Het stelseldocument is geldig" },
  ONGELDIG: { variant: "critical", tekst: "Het stelseldocument is niet geldig" },
  NIET_GETOETST: { variant: "warning", tekst: "Het stelseldocument is niet getoetst" },
} as const;

const datum = (iso: string) =>
  new Intl.DateTimeFormat("nl-NL", { dateStyle: "long", timeStyle: "short", timeZone: "Europe/Amsterdam" }).format(new Date(iso));

export function StelselPagina() {
  const [overzicht, setOverzicht] = useState<Stelseloverzicht>();
  const [fout, setFout] = useState<string>();

  useEffect(() => {
    haalStelseloverzicht().then(setOverzicht, (e: Error) => setFout(e.message));
  }, []);

  return (
    <nldd-simple-section>
      <nldd-title size="display-2">
        <h1>Aangesloten magazijnen</h1>
      </nldd-title>
      {fout && <nldd-banner variant="critical" text="Geen stelseldocument" supporting-text={fout} />}
      {!fout && !overzicht && <p>Het stelseldocument wordt opgehaald.</p>}
      {overzicht && <Overzicht overzicht={overzicht} />}
    </nldd-simple-section>
  );
}

function Overzicht({ overzicht }: { overzicht: Stelseloverzicht }) {
  const { verificatie, document, ondertekening, organisaties } = overzicht;
  const melding = VERIFICATIE[verificatie.status];

  return (
    <>
      <nldd-banner variant={melding.variant} text={melding.tekst} supporting-text={verificatie.reden} />
      {document && (
        <nldd-rich-text>
          <p>
            Uitgegeven door {document.uitgever} voor de omgeving {document.omgeving}, op{" "}
            {datum(document.uitgegevenOp)}. Geldig tot {datum(document.geldigTot)}.
          </p>
          {ondertekening && <p>Het ondertekencertificaat verloopt op {datum(ondertekening.certificaatGeldigTot)}.</p>}
        </nldd-rich-text>
      )}
      <nldd-table columns="minmax(200px,1fr) 220px minmax(240px,1fr)" accessible-label="Aangesloten magazijnen">
        <nldd-table-row slot="header">
          <nldd-text-cell text="Organisatie" />
          <nldd-text-cell text="OIN" />
          <nldd-text-cell text="Adres van het magazijn" />
        </nldd-table-row>
        {organisaties.map((o) => (
          <nldd-table-row key={o.oin}>
            <nldd-text-cell row-header text={o.naam} />
            <nldd-text-cell text={o.oin} />
            <nldd-text-cell text={o.magazijnUrl} />
          </nldd-table-row>
        ))}
      </nldd-table>
      {organisaties.length === 0 && <p>Er zijn geen organisaties aangesloten.</p>}
    </>
  );
}
