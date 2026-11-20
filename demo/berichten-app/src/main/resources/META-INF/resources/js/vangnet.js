// Een gewoon script, geen module. Laadt app.js niet (een ontbrekend of kapot bestand, zoals jose), dan
// draait er geen enkele regel van de app en blijft de pagina op "Laden…" staan. Dit script ziet dat
// aan het ontbrekende teken dat app.js bij de start zet, en zegt het de gebruiker.
//
// Een bestand dat niet te laden is, geeft een error op het module-script; een bestand dat niet te
// parsen is, een error op window. De time-out is alleen voor wat geen van beide meldt, en ruim, zodat
// een trage verbinding geen vals alarm geeft.
const TIJDSLIMIET_MS = 30_000;

function meld() {
  if (document.documentElement.dataset.gestart) return;

  const status = document.getElementById('status');

  if (status) status.textContent = 'De app kon niet laden. Herlaad de pagina; helpt dat niet, meld het dan aan de beheerder.';
}

document.querySelector('script[type="module"]')?.addEventListener('error', meld);
addEventListener('error', meld);
setTimeout(meld, TIJDSLIMIET_MS);
