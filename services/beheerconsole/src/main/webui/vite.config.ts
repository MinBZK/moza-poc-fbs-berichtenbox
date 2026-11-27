import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Vite zet het basispad in de asset-adressen en de router leest het terug via
// import.meta.env.BASE_URL; het komt uit BEHEERCONSOLE_BASE. Draait de console onder een subpad,
// dan moet quarkus.http.root-path mee.
export default defineConfig({
  base: process.env.BEHEERCONSOLE_BASE ?? "/",
  plugins: [react()],
  build: {
    outDir: "dist",
    // Geen data:-URL's voor fonts en media: font-src en media-src vallen terug op default-src 'self'.
    assetsInlineLimit: 0,
  },
});
