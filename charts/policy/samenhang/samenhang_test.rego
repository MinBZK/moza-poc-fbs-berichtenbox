# SPDX-License-Identifier: EUPL-1.2
package samenhang

import rego.v1

pod_labels := {"app.kubernetes.io/name": "dienst", "app.kubernetes.io/instance": "t", "app.kubernetes.io/component": "api"}

selector := {"matchLabels": {"app.kubernetes.io/name": "dienst", "app.kubernetes.io/instance": "t"}}

deployment := {
	"kind": "Deployment",
	"metadata": {"name": "t-dienst"},
	"spec": {"template": {
		"metadata": {"labels": pod_labels},
		"spec": {"serviceAccountName": "t-dienst"},
	}},
}

networkpolicy := {"kind": "NetworkPolicy", "metadata": {"name": "t-dienst"}, "spec": {"podSelector": selector}}

pdb := {"kind": "PodDisruptionBudget", "metadata": {"name": "t-dienst"}, "spec": {"selector": selector}}

serviceaccount := {"kind": "ServiceAccount", "metadata": {"name": "t-dienst"}}

service := {"kind": "Service", "metadata": {"name": "t-dienst"}, "spec": {"ports": [{"name": "http", "port": 80}]}}

route(naam, poort) := {"kind": "HTTPRoute", "metadata": {"name": "r"}, "spec": {"rules": [{"backendRefs": [{"name": naam, "port": poort}]}]}}

release(objs) := [{"path": "t.yaml", "contents": o} | some o in objs]

compleet := [deployment, networkpolicy, pdb, serviceaccount, service, route("t-dienst", 80)]

test_complete_release_slaagt if {
	count(deny) == 0 with input as release(compleet)
}

test_lege_release_slaagt if {
	count(deny) == 0 with input as release([])
}

zonder(kind) := [o | some o in compleet; o.kind != kind]

test_zonder_networkpolicy_faalt if {
	deny == {"Deployment 't-dienst' valt onder geen enkele NetworkPolicy"} with input as release(zonder("NetworkPolicy"))
}

test_networkpolicy_voor_andere_pods_faalt if {
	andere := json.patch(networkpolicy, [{"op": "replace", "path": "/spec/podSelector/matchLabels/app.kubernetes.io~1name", "value": "ander"}])
	objs := array.concat(zonder("NetworkPolicy"), [andere])
	deny == {"Deployment 't-dienst' valt onder geen enkele NetworkPolicy"} with input as release(objs)
}

test_zonder_pdb_faalt if {
	deny == {"Deployment 't-dienst' heeft geen PodDisruptionBudget"} with input as release(zonder("PodDisruptionBudget"))
}

test_zonder_serviceaccount_faalt if {
	deny == {"Deployment 't-dienst' verwijst naar service-account 't-dienst', dat de chart niet aanmaakt"} with input as release(zonder("ServiceAccount"))
}

test_route_naar_onbekende_service_faalt if {
	objs := array.concat(zonder("HTTPRoute"), [route("ander", 80)])
	deny == {"HTTPRoute 'r' wijst naar ander:80, maar die Service-poort bestaat niet"} with input as release(objs)
}

test_route_naar_verkeerde_poort_faalt if {
	objs := array.concat(zonder("HTTPRoute"), [route("t-dienst", 8086)])
	deny == {"HTTPRoute 'r' wijst naar t-dienst:8086, maar die Service-poort bestaat niet"} with input as release(objs)
}

test_meerdere_deployments_elk_beoordeeld if {
	tweede := json.patch(deployment, [
		{"op": "replace", "path": "/metadata/name", "value": "t-tweede"},
		{"op": "replace", "path": "/spec/template/metadata/labels/app.kubernetes.io~1name", "value": "tweede"},
	])
	objs := array.concat(compleet, [tweede])
	deny == {
		"Deployment 't-tweede' valt onder geen enkele NetworkPolicy",
		"Deployment 't-tweede' heeft geen PodDisruptionBudget",
	} with input as release(objs)
}
