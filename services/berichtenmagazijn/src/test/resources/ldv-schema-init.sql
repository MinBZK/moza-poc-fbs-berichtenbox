-- Staat los van Flyway: dit script draait bij het opstarten van de Dev-Services-container,
-- vóór de applicatie boot. De LDV-exporter maakt zijn tabel aan zodra hij geconstrueerd
-- wordt, dus het schema moet er dan al zijn; een @BeforeEach zou te laat komen.
CREATE SCHEMA IF NOT EXISTS magazijnschema;
