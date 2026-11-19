// Resolve-hook voor jose-uit-npm.mjs. Dezelfde URL als `import 'jose'`, zodat tests en browsercode
// één exemplaar van de library delen (en dus dezelfde foutklassen). Synchroon, zodat hij voor
// `registerHooks` én voor `register` werkt.
const JOSE = new URL('../../../node_modules/jose/dist/webapi/index.js', import.meta.url).href;

export function resolve(specifier, context, nextResolve) {
  if (specifier.endsWith('/vendor/jose/index.js')) return { url: JOSE, shortCircuit: true };

  return nextResolve(specifier, context);
}
