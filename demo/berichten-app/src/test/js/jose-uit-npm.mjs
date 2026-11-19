// Laad met `node --import <dit bestand>`. De browsercode importeert jose als `../vendor/jose/index.js`,
// het pad waar de Maven-build hem in de jar zet. Onder Node bestaat dat pad in src/main/resources
// niet; deze hook laat het naar dezelfde bestanden in node_modules wijzen, zodat de tests precies de
// versie uit package-lock.json draaien.
import { register } from 'node:module';

register('./jose-haak.mjs', import.meta.url);
