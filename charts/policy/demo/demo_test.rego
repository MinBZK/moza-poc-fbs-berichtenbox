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
	])
}
