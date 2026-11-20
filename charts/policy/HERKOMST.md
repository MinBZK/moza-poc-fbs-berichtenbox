# Herkomst van de beleidsregels

`containers.rego`, `image.rego`, `labels.rego` en `namespace.rego` zijn ongewijzigde kopieën uit
`policy/` van [MinBZK/mijn-bureau-infra](https://github.com/MinBZK/mijn-bureau-infra), commit
`a5c60bf6e65ac6dadd4d044be92aca9e760c8412` (2026-11-08, EUPL-1.2). Zo houdt de chart zich aan
dezelfde regels als de charts van mijn-bureau. Werk ze in hun geheel bij vanuit de bron, en pas de
commit hierboven aan.

`fbs.rego` en `samenhang/` zijn eigen regels voor deze chart, met tests in `*_test.rego`.
