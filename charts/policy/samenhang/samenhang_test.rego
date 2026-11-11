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
		"spec": {
			"serviceAccountName": "t-dienst",
			"containers": [{
				"name": "dienst",
				"ports": [{"name": "http", "containerPort": 8086}],
				"volumeMounts": [{"name": "tmp", "mountPath": "/tmp"}],
			}],
			"volumes": [{"name": "tmp", "emptyDir": {}}],
		},
	}},
}

networkpolicy := {"kind": "NetworkPolicy", "metadata": {"name": "t-dienst"}, "spec": {"podSelector": selector}}

pdb := {"kind": "PodDisruptionBudget", "metadata": {"name": "t-dienst"}, "spec": {"selector": selector}}

serviceaccount := {"kind": "ServiceAccount", "metadata": {"name": "t-dienst"}}

service := {"kind": "Service", "metadata": {"name": "t-dienst"}, "spec": {
	"selector": pod_labels,
	"ports": [{"name": "http", "port": 80, "targetPort": "http"}],
}}

route(naam, poort) := {"kind": "HTTPRoute", "metadata": {"name": "r"}, "spec": {"rules": [{"backendRefs": [{"name": naam, "port": poort}]}]}}

ingress(naam, poort) := {"kind": "Ingress", "metadata": {"name": "i"}, "spec": {"rules": [{"http": {"paths": [{"backend": {"service": {"name": naam, "port": poort}}}]}}]}}

hpa(naam) := {"kind": "HorizontalPodAutoscaler", "metadata": {"name": "h"}, "spec": {"scaleTargetRef": {"name": naam}}}

release(objs) := [{"path": "t.yaml", "contents": o} | some o in objs]

compleet := [deployment, networkpolicy, pdb, serviceaccount, service, route("t-dienst", 80), ingress("t-dienst", {"name": "http"}), hpa("t-dienst")]

zonder(kind) := [o | some o in compleet; o.kind != kind]

met(kind, vervanging) := array.concat(zonder(kind), [vervanging])

test_complete_release_slaagt if {
	count(deny) == 0 with input as release(compleet)
}

test_lege_release_slaagt if {
	count(deny) == 0 with input as release([])
}

test_zonder_networkpolicy_faalt if {
	deny == {"Deployment 't-dienst' valt onder geen enkele NetworkPolicy"} with input as release(zonder("NetworkPolicy"))
}

test_networkpolicy_voor_andere_pods_faalt if {
	andere := json.patch(networkpolicy, [{"op": "replace", "path": "/spec/podSelector/matchLabels/app.kubernetes.io~1name", "value": "ander"}])
	deny == {"Deployment 't-dienst' valt onder geen enkele NetworkPolicy"} with input as release(met("NetworkPolicy", andere))
}

test_zonder_pdb_faalt if {
	deny == {"Deployment 't-dienst' heeft geen PodDisruptionBudget"} with input as release(zonder("PodDisruptionBudget"))
}

test_zonder_serviceaccount_faalt if {
	deny == {"Deployment 't-dienst' verwijst naar service-account 't-dienst', dat de chart niet aanmaakt"} with input as release(zonder("ServiceAccount"))
}

test_route_naar_verkeerde_service_of_poort_faalt if {
	every geval in [["ander", 80], ["t-dienst", 8086]] {
		deny == {sprintf("HTTPRoute 'r' wijst naar %s:%v, maar die Service-poort bestaat niet", geval)} with input as release(met("HTTPRoute", route(geval[0], geval[1])))
	}
}

test_ingress_naar_service_bij_nummer_slaagt if {
	count(deny) == 0 with input as release(met("Ingress", ingress("t-dienst", {"number": 80})))
}

test_ingress_naar_verkeerde_poort_faalt if {
	every geval in [[{"name": "web"}, "web"], [{"number": 8086}, 8086]] {
		deny == {sprintf("Ingress 'i' wijst naar t-dienst:%v, maar die Service-poort bestaat niet", [geval[1]])} with input as release(met("Ingress", ingress("t-dienst", geval[0])))
	}
}

test_service_zonder_pods_faalt if {
	andere := json.patch(service, [{"op": "replace", "path": "/spec/selector/app.kubernetes.io~1component", "value": "web"}])
	# Zonder geselecteerde pods vindt ook de targetPort geen container.
	deny == {
		"Service 't-dienst' selecteert geen pods van de release",
		"Service 't-dienst' wijst naar targetPort http, maar geen geselecteerde container heeft die poort",
	} with input as release(met("Service", andere))
}

test_targetport_zonder_containerpoort_faalt if {
	every doel in ["web", 9999] {
		andere := json.patch(service, [{"op": "replace", "path": "/spec/ports/0/targetPort", "value": doel}])
		deny == {sprintf("Service 't-dienst' wijst naar targetPort %v, maar geen geselecteerde container heeft die poort", [doel])} with input as release(met("Service", andere))
	}
}

test_targetport_als_nummer_slaagt if {
	andere := json.patch(service, [{"op": "replace", "path": "/spec/ports/0/targetPort", "value": 8086}])
	count(deny) == 0 with input as release(met("Service", andere))
}

test_zonder_targetport_geldt_de_poort if {
	andere := json.patch(service, [{"op": "remove", "path": "/spec/ports/0/targetPort"}])
	deny == {"Service 't-dienst' wijst naar targetPort 80, maar geen geselecteerde container heeft die poort"} with input as release(met("Service", andere))
}

# Een poort die alleen in een ander Deployment bestaat, telt niet; bij naam en bij nummer.
test_targetport_in_ander_deployment_faalt if {
	ander := json.patch(deployment, [
		{"op": "replace", "path": "/metadata/name", "value": "t-ander"},
		{"op": "replace", "path": "/spec/template/metadata/labels/app.kubernetes.io~1name", "value": "ander"},
		{"op": "replace", "path": "/spec/template/spec/containers/0/ports/0/name", "value": "web"},
		{"op": "replace", "path": "/spec/template/spec/containers/0/ports/0/containerPort", "value": 9999},
	])
	every doel in ["web", 9999] {
		svc := json.patch(service, [{"op": "replace", "path": "/spec/ports/0/targetPort", "value": doel}])
		objs := array.concat(met("Service", svc), [ander])
		sprintf("Service 't-dienst' wijst naar targetPort %v, maar geen geselecteerde container heeft die poort", [doel]) in deny with input as release(objs)
	}
}

test_mount_zonder_volume_faalt if {
	every pad in ["/spec/template/spec/containers/0/volumeMounts/0/name", "/spec/template/spec/volumes/0/name"] {
		d := json.patch(deployment, [{"op": "replace", "path": pad, "value": "keystore2"}])
		naam := ["tmp", "keystore2"][to_number(contains(pad, "volumeMounts"))]
		deny == {sprintf("Deployment 't-dienst' mount '%s', maar heeft geen volume met die naam", [naam])} with input as release(met("Deployment", d))
	}
}

test_route_zonder_poort_faalt if {
	zonder_poort := {"kind": "HTTPRoute", "metadata": {"name": "r"}, "spec": {"rules": [{"backendRefs": [{"name": "t-dienst"}]}]}}
	deny == {"HTTPRoute 'r' wijst naar t-dienst zonder poort"} with input as release(met("HTTPRoute", zonder_poort))
}

test_hpa_naar_onbekende_deployment_faalt if {
	deny == {"HPA 'h' schaalt 'ander', maar die Deployment staat niet in de release"} with input as release(met("HorizontalPodAutoscaler", hpa("ander")))
}

test_meerdere_deployments_elk_beoordeeld if {
	tweede := json.patch(deployment, [
		{"op": "replace", "path": "/metadata/name", "value": "t-tweede"},
		{"op": "replace", "path": "/spec/template/metadata/labels/app.kubernetes.io~1name", "value": "tweede"},
	])
	deny == {
		"Deployment 't-tweede' valt onder geen enkele NetworkPolicy",
		"Deployment 't-tweede' heeft geen PodDisruptionBudget",
	} with input as release(array.concat(compleet, [tweede]))
}
