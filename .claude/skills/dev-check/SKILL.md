---
name: dev-check
description: Controleer of de lokale dev-omgeving draait (Redis, WireMock, PostgreSQL)
disable-model-invocation: true
---

# Dev-omgeving check

Controleer dat alle Docker Compose services beschikbaar zijn voor lokale ontwikkeling.

## Stappen

1. **Check container status**
   ```bash
   docker compose ps
   ```
   Verwacht: `redis`, `magazijn-a`, `magazijn-b`, `postgres-a`, `postgres-b`,
   `postgres-uitvraag` moeten status UP hebben.

2. **Start containers als ze niet draaien**
   ```bash
   docker compose up -d
   ```

3. **Health checks**
   - Redis: `docker compose exec redis redis-cli ping` → moet `PONG` teruggeven
   - PostgreSQL (magazijn-a): `docker compose exec postgres-a pg_isready -U berichtenmagazijn -d berichtenmagazijn` → moet `accepting connections` teruggeven
   - WireMock A: `curl -s http://localhost:8081/__admin/mappings` → moet JSON teruggeven
   - WireMock B: `curl -s http://localhost:8082/__admin/mappings` → moet JSON teruggeven

4. **Rapporteer resultaat**
   Geef een overzicht van welke services draaien en welke niet.
