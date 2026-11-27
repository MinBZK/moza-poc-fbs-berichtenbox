import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Het basispad komt uit één instelling: Vite zet het in de asset-adressen en de router leest het
// terug via import.meta.env.BASE_URL. Een portaal onder een subpad (zoals het NMC-portaal onder
// /moza-nmc-portaal/) zet alleen BEHEERCONSOLE_BASE.
export default defineConfig({
  base: process.env.BEHEERCONSOLE_BASE ?? "/",
  plugins: [react()],
  build: {
    outDir: "dist",
    // Geen inline assets: de CSP staat alleen scripts en styles van de eigen origin toe.
    assetsInlineLimit: 0,
  },
});
