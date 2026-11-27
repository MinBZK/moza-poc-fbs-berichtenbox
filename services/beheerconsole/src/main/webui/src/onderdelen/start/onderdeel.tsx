import type { Onderdeel } from "../../shell/onderdeel";
import { StartPagina } from "./StartPagina";

export const start: Onderdeel = {
  naam: "start",
  schermen: [{ pad: "/", menutekst: "Start", icoon: "house", inhoud: <StartPagina /> }],
};
