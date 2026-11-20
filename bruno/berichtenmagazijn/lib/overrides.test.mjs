// Een override in package.json geldt voor de hele boom en wint zonder melding, ook van een hogere
// versie of een andere major die een pakket in de boom van de CLI zelf vraagt. Deze test faalt dan.
// Hij leest de lockfile, dus wat gecommit is, en heeft geen `npm ci` nodig.
//
//   npm test
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';

const lees = (bestand) => JSON.parse(readFileSync(new URL(`../${bestand}`, import.meta.url), 'utf8'));
const { overrides = {} } = lees('package.json');
const { packages } = lees('package-lock.json');

// Ook geneste kopieën: `node_modules/@usebruno/cli/node_modules/@usebruno/js`.
const bruno = Object.entries(packages).filter(([pad]) => /(^|\/)node_modules\/@usebruno\/[^/]+$/.test(pad));

/** De versie die Node vanuit [pad] voor [naam] vindt: eerst genest, dan steeds een niveau hoger. */
function gevonden(pad, naam) {
  for (let basis = pad; ;) {
    const kandidaat = packages[`${basis ? `${basis}/` : ''}node_modules/${naam}`];

    if (kandidaat) return kandidaat.version;

    const hoger = basis.replace(/\/?node_modules\/(@[^/]+\/)?[^/]+$/, '');

    if (basis === '' || hoger === basis) return undefined;

    basis = hoger;
  }
}

/** De ondergrens van een spec als `1.2.3`, `^1.2.3`, `~1.2.3` of `>=1.2.3`; anders `null`. */
function ondergrens(spec) {
  return /^(?:[\^~]|>=)?\s*v?(\d+\.\d+\.\d+)/.exec(spec)?.[1] ?? null;
}

function vergelijk(a, b) {
  const [x, y] = [a, b].map((v) => v.split('.').map(Number));

  return x[0] - y[0] || x[1] - y[1] || x[2] - y[2];
}

describe('overrides', () => {
  it('de lockfile bevat @usebruno-pakketten', () => assert.ok(bruno.length > 0));

  it('noemt alleen pakketten die de CLI echt gebruikt', () => {
    for (const naam of Object.keys(overrides)) {
      assert.ok(bruno.some(([, p]) => p.dependencies?.[naam]), `${naam} staat in overrides, maar geen @usebruno-pakket gebruikt het`);
    }
  });

  // Elk pakket in de boom, niet alleen @usebruno: de override zet ook hun afhankelijkheden.
  const afnemers = Object.entries(packages).filter(([pad]) => pad !== '');

  for (const naam of Object.keys(overrides)) {
    it(`${naam} past bij wat elk pakket in de boom vraagt`, () => {
      for (const [pad, pakket] of afnemers) {
        const spec = { ...pakket.peerDependencies, ...pakket.optionalDependencies, ...pakket.dependencies }[naam];

        if (!spec) continue;

        const grens = ondergrens(spec);
        const versie = gevonden(pad, naam);

        assert.ok(grens, `${pad} vraagt ${naam}@${spec}; die vorm kent deze test niet`);
        assert.ok(versie, `${pad} vraagt ${naam}, maar de lockfile heeft het niet`);
        assert.ok(vergelijk(versie, grens) >= 0, `${pad} vraagt ${naam}@${spec}, de override geeft ${versie}`);

        // Een exacte versie of `^` belooft dezelfde major; een override mag daar niet overheen.
        if (/^(\^|v?\d)/.test(spec.trim())) {
          assert.equal(versie.split('.')[0], grens.split('.')[0], `${pad} vraagt ${naam}@${spec}, de override geeft ${versie}`);
        }
      }
    });
  }
});
