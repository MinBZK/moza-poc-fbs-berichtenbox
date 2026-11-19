// Vervangt vendor/jose door dist/webapi uit node_modules/jose, en schrijft erbij welke versie en
// integrity het is. Eerst `npm ci --ignore-scripts`: die toetst het pakket aan de integrity in
// package-lock.json, dus wat hier gekopieerd wordt, is precies wat de lockfile vastlegt.
// VendorJoseTest eist daarna dat BRON.json bij de lockfile past.
import { cpSync, readFileSync, rmSync, writeFileSync } from 'node:fs';

const lock = JSON.parse(readFileSync('package-lock.json', 'utf8')).packages['node_modules/jose'];
const geinstalleerd = JSON.parse(readFileSync('node_modules/jose/package.json', 'utf8')).version;

if (geinstalleerd !== lock.version) {
  throw new Error(`node_modules heeft jose ${geinstalleerd}, de lockfile ${lock.version}: draai eerst npm ci --ignore-scripts`);
}

const doel = 'src/main/resources/META-INF/resources/vendor/jose';

rmSync(doel, { recursive: true, force: true });
cpSync('node_modules/jose/dist/webapi', doel, { recursive: true });
cpSync('node_modules/jose/LICENSE.md', `${doel}/LICENSE.md`);
writeFileSync(`${doel}/BRON.json`, `${JSON.stringify({ pakket: 'jose', versie: lock.version, integrity: lock.integrity }, null, 2)}\n`);

console.log(`vendor/jose is jose ${lock.version}`);
