# SPDX-License-Identifier: EUPL-1.2
package demo

import rego.v1

als_invoer(objecten) := [{"path": "x.yaml", "contents": o} | some o in objecten]

pod(naam, labels, volumes) := {
	"kind": "Deployment",
	"metadata": {"name": naam},
	"spec": {"template": {
		"metadata": {"labels": object.union({"app": naam}, labels)},
		"spec": {"volumes": volumes},
	}},
}

# Een NetworkPolicy voor de pods met app=<doel>: binnen vanaf het clientlabel op `binnen`, uit naar
# elke bestemming op `uit`. Zonder poorten in `binnen` geen ingress-regel.
netpol(doel, deel_van, binnen, uit) := {
	"kind": "NetworkPolicy",
	"metadata": {"name": doel, "labels": {"app.kubernetes.io/part-of": deel_van}},
	"spec": {
		"podSelector": {"matchLabels": {"app": doel}},
		"ingress": [{"from": [{"podSelector": {"matchLabels": label(doel)}}], "ports": [{"port": p} | some p in binnen]} | count(binnen) > 0],
		"egress": [{"ports": [{"port": p} | some p in uit]}],
	},
}

label(doel) := {sprintf("client.fbs.minbzk.nl/%s", [doel]): "true"}

test_aanroeper_met_poort_slaagt if {
	count(deny) == 0 with input as als_invoer([
		pod("console", label("redis"), []),
		pod("redis", {}, []),
		netpol("console", "fbs-demo", [], [6379]),
		netpol("redis", "fbs-demo", [6379], []),
	])
}

test_aanroeper_zonder_poort_faalt if {
	deny == {"Deployment 'console' mag redis aanroepen op poort 6379, maar zijn NetworkPolicy laat die poort niet uit"} with input as als_invoer([
		pod("console", label("redis"), []),
		pod("redis", {}, []),
		netpol("console", "fbs-demo", [], [5432]),
		netpol("redis", "fbs-demo", [6379], []),
	])
}

# Een egress-regel met `to` geldt alleen voor die bestemming, niet voor het doel van het label.
test_poort_alleen_naar_andere_bestemming_faalt if {
	np := {
		"kind": "NetworkPolicy",
		"metadata": {"name": "console", "labels": {"app.kubernetes.io/part-of": "fbs-demo"}},
		"spec": {"podSelector": {"matchLabels": {"app": "console"}}, "egress": [{"to": [{"podSelector": {"matchLabels": {"x": "y"}}}], "ports": [{"port": 6379}]}]},
	}
	deny == {"Deployment 'console' mag redis aanroepen op poort 6379, maar zijn NetworkPolicy laat die poort niet uit"} with input as als_invoer([
		pod("console", label("redis"), []),
		pod("redis", {}, []),
		np,
		netpol("redis", "fbs-demo", [6379], []),
	])
}

test_clientlabel_zonder_networkpolicy_faalt if {
	# Een verschreven label valt van twee kanten op: niemand laat het binnen, en niemand draagt het
	# juiste.
	deny == {
		"Deployment 'console' draagt client.fbs.minbzk.nl/rdis, maar geen NetworkPolicy laat dat label binnen",
		"NetworkPolicy 'redis' laat client.fbs.minbzk.nl/redis binnen, maar geen pod draagt dat label",
	} with input as als_invoer([
		pod("console", label("rdis"), []),
		pod("redis", {}, []),
		netpol("console", "fbs-demo", [], [6379]),
		netpol("redis", "fbs-demo", [6379], []),
	])
}

test_clientlabel_met_andere_waarde_telt_niet if {
	count(deny) == 0 with input as als_invoer([
		pod("console", {"client.fbs.minbzk.nl/rdis": "false", "client.fbs.minbzk.nl/redis": "true"}, []),
		pod("redis", {}, []),
		netpol("console", "fbs-demo", [], [6379]),
		netpol("redis", "fbs-demo", [6379], []),
	])
}

test_demo_networkpolicy_zonder_aanroeper_faalt if {
	deny == {"NetworkPolicy 'redis' laat client.fbs.minbzk.nl/redis binnen, maar geen pod draagt dat label"} with input as als_invoer([
		pod("redis", {}, []),
		netpol("redis", "fbs-demo", [6379], []),
	])
}

# Een stelseldienst laat zijn clientlabel ook toe als niemand in de demo hem aanroept.
test_stelsel_networkpolicy_zonder_aanroeper_slaagt if {
	count(deny) == 0 with input as als_invoer([
		pod("stelselregister", {}, []),
		netpol("stelselregister", "fbs", [8094], []),
	])
}

test_configmap_van_andere_release_slaagt if {
	count(deny) == 0 with input as als_invoer([
		pod("uitvraag", {}, [{"name": "register", "configMap": {"name": "fbs-demo-register"}}]),
		{"kind": "ConfigMap", "metadata": {"name": "fbs-demo-register"}},
	])
}

test_configmap_die_niemand_maakt_faalt if {
	deny == {"Deployment 'uitvraag' mount ConfigMap 'fbs-demo-registr', maar geen release maakt die"} with input as als_invoer([
		pod("uitvraag", {}, [{"name": "register", "configMap": {"name": "fbs-demo-registr"}}, {"name": "ca", "secret": {"secretName": "db-ca"}}]),
		{"kind": "ConfigMap", "metadata": {"name": "fbs-demo-register"}},
		# Een object van een andere soort met dezelfde naam is geen ConfigMap.
		{"kind": "Service", "metadata": {"name": "fbs-demo-registr"}},
	])
}

# Een andere pod mag de poort wel uit; dat telt niet voor de aanroeper.
test_poort_alleen_bij_een_andere_pod_faalt if {
	deny == {"Deployment 'console' mag redis aanroepen op poort 6379, maar zijn NetworkPolicy laat die poort niet uit"} with input as als_invoer([
		pod("console", label("redis"), []),
		pod("toxiproxy", {}, []),
		pod("redis", {}, []),
		netpol("console", "fbs-demo", [], [5432]),
		netpol("toxiproxy", "fbs-demo", [], [6379]),
		netpol("redis", "fbs-demo", [6379], []),
	])
}

# Een peer met "false" laat niemand binnen: de aanroeper hoeft die poort dan ook niet uit.
test_peer_met_false_opent_geen_poort if {
	np := {
		"kind": "NetworkPolicy",
		"metadata": {"name": "redis", "labels": {"app.kubernetes.io/part-of": "fbs"}},
		"spec": {
			"podSelector": {"matchLabels": {"app": "redis"}},
			"ingress": [
				{"from": [{"podSelector": {"matchLabels": label("redis")}}], "ports": [{"port": 6379}]},
				{"from": [{"podSelector": {"matchLabels": {"client.fbs.minbzk.nl/redis": "false"}}}], "ports": [{"port": 9999}]},
			],
		},
	}
	count(deny) == 0 with input as als_invoer([
		pod("console", label("redis"), []),
		pod("redis", {}, []),
		netpol("console", "fbs-demo", [], [6379]),
		np,
	])
}

route_obj(naam, host, backend, poort) := {
	"kind": "HTTPRoute",
	"metadata": {"name": naam},
	"spec": {"hostnames": [host], "rules": [{"backendRefs": [{"name": backend, "port": poort}]}]},
}

svc(naam, poort, doel) := {"kind": "Service", "metadata": {"name": naam}, "spec": {"selector": {"app": naam}, "ports": [{"port": poort, "targetPort": doel}]}}

met_poort(d, naam, poort) := object.union(d, {"spec": {"template": {"spec": {"containers": [{"name": "c", "ports": [{"name": naam, "containerPort": poort}]}]}}}})

met_env(d, env) := object.union(d, {"spec": {"template": {"spec": {"containers": [{"name": "c", "env": env}]}}}})

gateway_np(doel, poorten) := {
	"kind": "NetworkPolicy",
	"metadata": {"name": doel, "labels": {"app.kubernetes.io/part-of": "fbs-demo"}},
	"spec": {
		"podSelector": {"matchLabels": {"app": doel}},
		"ingress": [{"from": [{"namespaceSelector": {"matchLabels": {"kubernetes.io/metadata.name": "gw"}}}], "ports": [{"port": p} | some p in poorten]}],
	},
}

test_route_naar_poort_die_de_gateway_binnenlaat_slaagt if {
	count(deny) == 0 with input as als_invoer([
		route_obj("aanmelding", "aanmelding.x.nl", "toxiproxy", 18086),
		svc("toxiproxy", 18086, "aanmeld"),
		met_poort(pod("toxiproxy", {}, []), "aanmeld", 18086),
		gateway_np("toxiproxy", [18089, 18086]),
	])
}

test_route_naar_poort_die_de_gateway_niet_binnenlaat_faalt if {
	deny == {"HTTPRoute 'aanmelding' wijst naar toxiproxy:18086, maar de NetworkPolicy van 'toxiproxy' laat de Gateway niet binnen op poort 18086"} with input as als_invoer([
		route_obj("aanmelding", "aanmelding.x.nl", "toxiproxy", 18086),
		svc("toxiproxy", 18086, "aanmeld"),
		met_poort(pod("toxiproxy", {}, []), "aanmeld", 18086),
		gateway_np("toxiproxy", [18089, 18084]),
	])
}

# Een clientlabel op die poort is geen Gateway: de proxy's draaien in een andere namespace.
test_route_met_alleen_clientlabel_op_de_poort_faalt if {
	deny == {"HTTPRoute 'demo' wijst naar proxy:80, maar de NetworkPolicy van 'proxy' laat de Gateway niet binnen op poort 8080"} with input as als_invoer([
		route_obj("demo", "demo.x.nl", "proxy", 80),
		svc("proxy", 80, 8080),
		met_poort(pod("proxy", {}, []), "http", 8080),
		pod("aanroeper", label("proxy"), []),
		netpol("aanroeper", "fbs-demo", [], [8080]),
		netpol("proxy", "fbs-demo", [8080], []),
	])
}

test_https_adres_met_route_slaagt if {
	count(deny) == 0 with input as als_invoer([
		route_obj("profiel", "profiel.x.nl", "p", 8080),
		svc("p", 8080, 8080),
		met_poort(pod("p", {}, []), "http", 8080),
		gateway_np("p", [8080]),
		met_env(pod("uitvraag", {}, []), [{"name": "PROFIEL_SERVICE_URL", "value": "https://profiel.x.nl"}, {"name": "ELDERS", "value": "https://profiel.mijnoverheidzakelijk.nl"}]),
	])
}

test_https_adres_zonder_route_faalt if {
	every adres in ["https://profielen.x.nl", "https://profielen.x.nl/api", "https://profielen.x.nl:443"] {
		deny == {sprintf("Deployment 'uitvraag' zet PROFIEL_SERVICE_URL op %s, maar geen HTTPRoute heeft hostnaam profielen.x.nl", [adres])} with input as als_invoer([
			route_obj("profiel", "profiel.x.nl", "p", 8080),
			svc("p", 8080, 8080),
			met_poort(pod("p", {}, []), "http", 8080),
			gateway_np("p", [8080]),
			met_env(pod("uitvraag", {}, []), [{"name": "PROFIEL_SERVICE_URL", "value": adres}]),
		])
	}
}

test_schema_van_paneel_en_magazijn_gelijk_slaagt if {
	count(deny) == 0 with input as als_invoer([
		met_env(pod("console", {}, []), [{"name": "MAGAZIJN_A_DB_SCHEMA", "value": "magazijn_a"}]),
		met_env(pod("berichtenmagazijn-a", {}, []), [{"name": "DB_SCHEMA", "value": "magazijn_a"}]),
	])
}

test_schema_van_paneel_en_magazijn_verschillend_faalt if {
	deny == {"Deployment 'console' leegt schema magazijn_x van berichtenmagazijn-a, maar dat magazijn gebruikt magazijn_a"} with input as als_invoer([
		met_env(pod("console", {}, []), [{"name": "MAGAZIJN_A_DB_SCHEMA", "value": "magazijn_x"}, {"name": "MAGAZIJN_B_DB_SCHEMA", "value": "magazijn_b"}]),
		met_env(pod("berichtenmagazijn-a", {}, []), [{"name": "DB_SCHEMA", "value": "magazijn_a"}]),
		met_env(pod("berichtenmagazijn-b", {}, []), [{"name": "DB_SCHEMA", "value": "magazijn_b"}]),
	])
}

test_alleen_de_redis_klep_slaagt if {
	count(deny) == 0 with input as als_invoer([met_env(pod("uitvraag", {}, []), [{"name": "FBS_REDIS_UNSAFE_ALLOW_PLAINTEXT", "value": "true"}])])
}

test_andere_klep_faalt if {
	every naam in ["FBS_LDV_UNSAFE_ALLOW_PLAINTEXT_ENDPOINT", "fbs.outway.unsafe-allow-unverified-tls"] {
		deny == {sprintf("Deployment 'uitvraag' zet %s; de demo laat alleen FBS_REDIS_UNSAFE_ALLOW_PLAINTEXT toe", [naam])} with input as als_invoer([
			met_env(pod("uitvraag", {}, []), [{"name": "FBS_REDIS_UNSAFE_ALLOW_PLAINTEXT", "value": "true"}, {"name": naam, "value": "true"}]),
		])
	}
}

# Met meerdere poorten telt die met de naam uit targetPort, niet de eerste.
test_route_naar_benoemde_poort_tussen_meerdere_faalt if {
	d := object.union(pod("toxiproxy", {}, []), {"spec": {"template": {"spec": {"containers": [{"name": "c", "ports": [
		{"name": "profiel", "containerPort": 18089},
		{"name": "aanmeld", "containerPort": 18086},
	]}]}}}})
	deny == {"HTTPRoute 'aanmelding' wijst naar toxiproxy:18086, maar de NetworkPolicy van 'toxiproxy' laat de Gateway niet binnen op poort 18086"} with input as als_invoer([
		route_obj("aanmelding", "aanmelding.x.nl", "toxiproxy", 18086),
		svc("toxiproxy", 18086, "aanmeld"),
		d,
		gateway_np("toxiproxy", [18089]),
	])
}

# --- Gateway, routes en kleppen (vervolg) ---

test_gateway_selectie_per_pod if {
	d := object.union(pod("toxiproxy", {}, []), {"spec": {"template": {"spec": {"containers": [{"name": "c", "ports": [{"name": "aanmeld", "containerPort": 18086}]}]}}}})
	deny == {"HTTPRoute 'aanmelding' wijst naar toxiproxy:18086, maar de NetworkPolicy van 'toxiproxy' laat de Gateway niet binnen op poort 18086"} with input as als_invoer([
		route_obj("aanmelding", "aanmelding.x.nl", "toxiproxy", 18086),
		svc("toxiproxy", 18086, "aanmeld"),
		d,
		pod("anders", {}, []),
		gateway_np("toxiproxy", [18089]),
		gateway_np("anders", [18086]),
	])
}

test_gateway_uit_twee_namespaces_faalt if {
	np2 := object.union(gateway_np("b", [80]), {"spec": {"ingress": [{"from": [{"namespaceSelector": {"matchLabels": {"kubernetes.io/metadata.name": "gw2"}}}], "ports": [{"port": 80}]}]}})
	deny == {`De NetworkPolicies laten de Gateway uit verschillende namespaces binnen: ["{\"kubernetes.io/metadata.name\":\"gw\"}", "{\"kubernetes.io/metadata.name\":\"gw2\"}"]`} with input as als_invoer([
		gateway_np("a", [80]),
		np2,
	])
}

test_klep_via_value_from_faalt if {
	deny == {"Deployment 'uitvraag' zet FBS_LDV_UNSAFE_ALLOW_PLAINTEXT_ENDPOINT; de demo laat alleen FBS_REDIS_UNSAFE_ALLOW_PLAINTEXT toe"} with input as als_invoer([
		met_env(pod("uitvraag", {}, []), [{"name": "FBS_LDV_UNSAFE_ALLOW_PLAINTEXT_ENDPOINT", "valueFrom": {"configMapKeyRef": {"name": "x", "key": "y"}}}]),
	])
}

test_https_adres_dieper_onder_het_domein_faalt if {
	deny == {"Deployment 'uitvraag' zet MAGAZIJN_A_URL op https://berichtenmagazijn-a.demo.x.nl, maar geen HTTPRoute heeft hostnaam berichtenmagazijn-a.demo.x.nl"} with input as als_invoer([
		route_obj("profiel", "profiel.x.nl", "p", 8080),
		svc("p", 8080, 8080),
		met_poort(pod("p", {}, []), "http", 8080),
		gateway_np("p", [8080]),
		met_env(pod("uitvraag", {}, []), [{"name": "MAGAZIJN_A_URL", "value": "https://berichtenmagazijn-a.demo.x.nl"}]),
	])
}

test_schema_zonder_magazijn_faalt if {
	every invoer in [
		[met_env(pod("console", {}, []), [{"name": "MAGAZIJN_A_DB_SCHEMA", "value": "magazijn_a"}])],
		[
			met_env(pod("console", {}, []), [{"name": "MAGAZIJN_A_DB_SCHEMA", "value": "magazijn_a"}]),
			met_env(pod("berichtenmagazijn-a", {}, []), [{"name": "MAGAZIJN_OIN", "value": "1"}]),
		],
	] {
		deny == {"Deployment 'console' leegt schema magazijn_a van berichtenmagazijn-a, maar er is geen Deployment berichtenmagazijn-a met DB_SCHEMA"} with input as als_invoer(invoer)
	}
}

route_met(naam, labelnaam, regels) := {
	"kind": "HTTPRoute",
	"metadata": {"name": naam, "labels": {"app.kubernetes.io/name": labelnaam}},
	"spec": {"hostnames": ["x.nl"], "rules": regels},
}

pad(type, waarde) := {"path": {"type": type, "value": waarde}}

test_simulator_met_de_drie_methoden_slaagt if {
	count(deny) == 0 with input as als_invoer([route_met("sim", "simulator", [{"matches": [
		object.union(pad("PathPrefix", "/magazijn/"), {"method": "GET"}),
		object.union(pad("PathPrefix", "/magazijn/"), {"method": "PATCH"}),
		object.union(pad("PathPrefix", "/magazijn/"), {"method": "DELETE"}),
	]}])])
}

test_simulator_met_andere_methode_faalt if {
	every geval in [
		{"match": object.union(pad("PathPrefix", "/magazijn/"), {"method": "POST"}), "getoond": "POST"},
		{"match": object.union(pad("PathPrefix", "/magazijn/"), {"method": "PUT"}), "getoond": "PUT"},
		{"match": pad("PathPrefix", "/magazijn/"), "getoond": "elke"},
	] {
		deny == {sprintf("HTTPRoute 'sim' laat methode '%s' door; alleen GET, PATCH en DELETE", [geval.getoond])} with input as als_invoer([route_met("sim", "simulator", [{"matches": [
			object.union(pad("PathPrefix", "/magazijn/"), {"method": "GET"}),
			object.union(pad("PathPrefix", "/magazijn/"), {"method": "PATCH"}),
			object.union(pad("PathPrefix", "/magazijn/"), {"method": "DELETE"}),
			geval.match,
		]}])])
	}
}

test_simulator_zonder_patch_faalt if {
	deny == {`HTTPRoute 'sim' mist methode(n) ["PATCH"]; de uitvraag gebruikt ze`} with input as als_invoer([route_met("sim", "simulator", [{"matches": [
		object.union(pad("PathPrefix", "/magazijn/"), {"method": "GET"}),
		object.union(pad("PathPrefix", "/magazijn/"), {"method": "DELETE"}),
	]}])])
}

test_magazijn_alleen_berichten_slaagt if {
	count(deny) == 0 with input as als_invoer([route_met("m", "berichtenmagazijn", [{"matches": [pad("PathPrefix", "/api/v1/berichten"), pad("Exact", "/openapi.json")]}])])
}

test_magazijn_met_ander_pad_faalt if {
	every waarde in ["/api/v1/aanleveringen", "/api/v1", "/api", "/"] {
		deny == {sprintf(`HTTPRoute 'm' laat %s door; alleen ["/api/v1/berichten", "/openapi.json"]`, [waarde])} with input as als_invoer([route_met("m", "berichtenmagazijn", [{"matches": [pad("PathPrefix", "/api/v1/berichten"), pad("PathPrefix", waarde)]}])])
	}
}

test_streams_met_0s_en_rest_met_grens_slaagt if {
	count(deny) == 0 with input as als_invoer([route_met("demo", "demo", [
		{"matches": [pad("PathPrefix", "/")], "timeouts": {"request": "5m"}},
		{"matches": [pad("Exact", "/api/v1/berichten/_ophalen"), pad("Exact", "/api/v1/berichten/_volgen")], "timeouts": {"request": "0s"}},
	])])
}

test_streams_omgedraaid_faalt if {
	deny == {
		"HTTPRoute 'demo': / zonder grens op de request-timeout; alleen de streams",
		"HTTPRoute 'demo': stream /api/v1/berichten/_ophalen zonder timeouts.request 0s; Envoy breekt hem anders af",
	} with input as als_invoer([route_met("demo", "demo", [
		{"matches": [pad("PathPrefix", "/")], "timeouts": {"request": "0s"}},
		{"matches": [pad("Exact", "/api/v1/berichten/_ophalen")], "timeouts": {"request": "5m"}},
	])])
}
