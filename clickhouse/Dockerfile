# ClickHouse voor ZAD met enkel console-logging erin gebakken. De officiële
# clickhouse-server logt naar bestanden in de container, niet naar stdout, en geen env-var
# zet dat om → fouten bleven onzichtbaar in 'kubectl logs'/de UI. config.d/logging.xml zet
# console=1. Géén geheugen-/merge-tuning meer: geef de instance genoeg geheugen via het
# container-limit (~2Gi). Pin gelijk houden met compose.yaml (zie pin-consistency.yml).
#
# Overbodig zodra ZAD de component-command/args ondersteunt: draai dan het stock-image met
# arg `--logger.console=1` en verwijder dit image + de build-clickhouse-job.
FROM clickhouse/clickhouse-server:26.5
COPY config.d/ /etc/clickhouse-server/config.d/
