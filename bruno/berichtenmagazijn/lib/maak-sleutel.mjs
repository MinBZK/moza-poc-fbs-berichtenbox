// Maakt de sleutel van een App Manager die alleen op deze machine bestaat, voor de Bruno-requests
// onder rechtstreeks/. De private key gaat in `bruno/berichtenmagazijn/.env` (gitignored), de
// publieke helft op stdout als de instellingen waarmee een lokale simulator hem vertrouwt. Bestaat
// `.env` al met een sleutel, dan hergebruikt het script die en toont het de instellingen opnieuw.
//
// De sleutel ontstaat pas hier en staat in geen enkel ingecheckt bestand. Dat hij alleen op een
// ontwikkelmachine vertrouwd wordt, is een afspraak: de simulator neemt de instellingen in elk profiel
// aan. Zet ze daarom nooit op een gedeelde of ZAD-omgeving. `geldig-tot` ligt negentig dagen vooruit,
// zodat een vergeten instelling vanzelf vervalt.
import { createPrivateKey, createPublicKey, generateKeyPairSync } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const { LOKALE_APP_MANAGER: { oin, kid } } = createRequire(import.meta.url)('./rechtstreeks.js');
const VARIABELE = 'RECHTSTREEKS_APP_MANAGER_SLEUTEL';
const env = fileURLToPath(new URL('../.env', import.meta.url));

function bestaande() {
  if (!existsSync(env)) return null;

  const regel = readFileSync(env, 'utf8').split('\n').find((r) => r.startsWith(`${VARIABELE}=`));

  return regel ? regel.slice(VARIABELE.length + 1).trim() : null;
}

const GELDIG_DAGEN = 90;
let pkcs8 = bestaande();

if (pkcs8 === null || pkcs8 === '') {
  if (existsSync(env)) {
    console.error(`${env} bestaat al, zonder bruikbare ${VARIABELE}. Voeg de sleutel niet blind toe: kijk eerst wat er staat.`);
    process.exit(1);
  }

  const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });

  pkcs8 = privateKey.export({ format: 'der', type: 'pkcs8' }).toString('base64url');
  writeFileSync(env, [
    '# Alleen voor lokaal gebruik: de sleutel van een App Manager die alleen op deze machine bestaat.',
    '# Gemaakt door lib/maak-sleutel.mjs; niet inchecken, niet delen, niet op een gedeelde omgeving zetten.',
    `${VARIABELE}=${pkcs8}`,
    '',
  ].join('\n'), { mode: 0o600 });
  console.error(`Nieuwe sleutel in ${env}`);
}

const { x, y } = createPublicKey(createPrivateKey({ key: Buffer.from(pkcs8, 'base64url'), format: 'der', type: 'pkcs8' })).export({ format: 'jwk' });
const geldigTot = new Date(Date.now() + GELDIG_DAGEN * 24 * 3600 * 1000).toISOString().replace(/\.\d{3}Z$/, 'Z');
const prefix = `-Dsimulator.rechtstreeks.app-managers.${oin}.sleutels.${kid}`;

console.error('Start de simulator met deze instellingen, alleen op deze machine:');
console.log(`${prefix}.x=${x} ${prefix}.y=${y} ${prefix}.geldig-tot=${geldigTot}`);
