import type { Onderdeel } from "../../shell/onderdeel";
import { StelselPagina } from "./StelselPagina";

export const stelsel: Onderdeel = {
  naam: "stelsel",
  schermen: [{ pad: "/stelsel", menutekst: "Magazijnen", icoon: "file-box", inhoud: <StelselPagina /> }],
};
