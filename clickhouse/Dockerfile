# Low-resource ClickHouse voor ZAD: het Logboek Dataverwerkingen (LDV) draait per
# project als ephemeral component (geen grote volumes, lage load). De stock
# clickhouse-server detecteert in deze cgroup soms het host-RAM i.p.v. de container-
# limit en over-alloceert → OOM-kill. De config.d-override hieronder capt het geheugen
# en de caches hard, zodat CH ruim binnen een kleine container-limit (~768Mi–1Gi) blijft.
# Pin gelijk houden met compose.yaml + deploy.yml (zie pin-consistency.yml).
FROM clickhouse/clickhouse-server:26.5
COPY config.d/ /etc/clickhouse-server/config.d/
