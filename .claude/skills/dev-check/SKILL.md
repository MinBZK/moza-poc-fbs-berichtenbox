---
name: dev-check
description: Controleer of de lokale dev-omgeving draait (Redis, WireMock, ClickHouse)
disable-model-invocation: true
---

# Dev-omgeving check

Controleer dat alle Docker Compose services beschikbaar zijn voor lokale ontwikkeling.

## Stappen

1. **Check container status**
   ```bash
   docker compose ps
   ```
   Verwacht: `redis`, `magazijn-a`, `magazijn-b`, `clickhouse` moeten status UP hebben.

2. **Start containers als ze niet draaien**
   ```bash
   docker compose up -d
   ```

3. **Health checks**
   - Redis: `docker compose exec redis redis-cli ping` → moet `PONG` teruggeven
   - ClickHouse: `curl -s http://localhost:8123/ping` → moet `Ok.` teruggeven
   - WireMock A: `curl -s http://localhost:8081/__admin/mappings` → moet JSON teruggeven
   - WireMock B: `curl -s http://localhost:8082/__admin/mappings` → moet JSON teruggeven

4. **Rapporteer resultaat**
   Geef een overzicht van welke services draaien en welke niet.
