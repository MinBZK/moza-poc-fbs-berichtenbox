// Laad met `node --import <dit bestand>`. De browsercode importeert jose als `../vendor/jose/index.js`,
// het pad waar de Maven-build hem in de jar zet. Onder Node bestaat dat pad in src/main/resources
// niet; deze hook laat het naar dezelfde bestanden in node_modules wijzen, zodat de tests precies de
// versie uit package-lock.json draaien.
//
// `registerHooks` waar Node het heeft (22.15 en later); `register` is daar verouderd en geeft een
// DeprecationWarning. Oudere versies (vanaf 20.6) hebben alleen `register`.
import * as module from 'node:module';
import { resolve } from './jose-haak.mjs';

if (module.registerHooks) module.registerHooks({ resolve });
else module.register('./jose-haak.mjs', import.meta.url);
