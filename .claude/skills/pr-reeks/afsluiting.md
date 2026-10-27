# Sjabloon afsluitcomment

Eén comment per PR, aan het eind van de reviewloop. Een extra ronde of live-test op verzoek krijgt
een eigen comment in dezelfde vorm. Vul alles in; laat een kop weg als er niets onder staat.

```markdown
## Review-afsluiting

<N> reviewrondes. Reviewers: <agents>, getoetst aan <overheidsskills>. Elke ronde lokaal groen
(<wat er draaide>). Mutatiecheck op nieuwe tests: <x> mutaties, <y> gevangen<, toelichting overlevers>.

| Ronde | Bevindingen | Afhandeling |
|---|---|---|
| 1 | **Hoog:** …<br>**Medium:** … | `<sha>` |
| N | Geen Hoog of Medium. **Laag:** … | `<sha>` <, na ronde N niet opnieuw gereviewd> |

**Bewust gelaten**
- … (reden)

**Open punten**
- Niet live getest: … Controleer na de merge: …
- Beslissing voor de opdrachtgever: …

🤖 Generated with [Claude Code](https://claude.com/claude-code)
```
