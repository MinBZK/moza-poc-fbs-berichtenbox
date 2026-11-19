// Een gewoon script, geen module. Laadt app.js niet (een ontbrekend of kapot bestand, zoals jose), dan
// draait er geen enkele regel van de app en blijft de pagina op "Laden…" staan. Dit script ziet dat
// aan het ontbrekende teken dat app.js bij de start zet, en zegt het de gebruiker.
setTimeout(() => {
  if (document.documentElement.dataset.gestart) return;

  const status = document.getElementById('status');

  if (status) status.textContent = 'De app kon niet laden. Herlaad de pagina; helpt dat niet, meld het dan aan de beheerder.';
}, 5000);
