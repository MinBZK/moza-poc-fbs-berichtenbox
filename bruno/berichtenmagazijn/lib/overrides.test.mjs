// Een override in package.json wint zonder melding, ook van een hogere versie die een nieuwe CLI
// zelf vastpint. Deze test faalt als een override zo een afhankelijkheid van de CLI terugzet.
//
//   npm test
import { readFileSync, readdirSync } from 'node:fs';
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';

const basis = new URL('../', import.meta.url);
const leesPakket = (pad) => JSON.parse(readFileSync(new URL(`${pad}/package.json`, basis), 'utf8'));
const { overrides = {} } = leesPakket('.');
const bruno = readdirSync(new URL('node_modules/@usebruno/', basis)).map((naam) => leesPakket(`node_modules/@usebruno/${naam}`));
const EXACT = /^\d+\.\d+\.\d+$/;

function vergelijk(a, b) {
  const [x, y] = [a, b].map((v) => v.split('.').map(Number));

  return x[0] - y[0] || x[1] - y[1] || x[2] - y[2];
}

describe('overrides', () => {
  it('noemt alleen pakketten die de CLI echt gebruikt', () => {
    for (const naam of Object.keys(overrides)) {
      assert.ok(bruno.some((p) => p.dependencies?.[naam]), `${naam} staat in overrides, maar geen @usebruno-pakket gebruikt het`);
    }
  });

  for (const naam of Object.keys(overrides)) {
    it(`${naam} staat niet lager dan wat een @usebruno-pakket exact vraagt`, () => {
      const geinstalleerd = leesPakket(`node_modules/${naam}`).version;

      for (const pakket of bruno) {
        const gevraagd = pakket.dependencies?.[naam];

        if (gevraagd && EXACT.test(gevraagd)) {
          assert.ok(vergelijk(geinstalleerd, gevraagd) >= 0, `${pakket.name} vraagt ${naam}@${gevraagd}, de override geeft ${geinstalleerd}`);
        }
      }
    });
  }
});
