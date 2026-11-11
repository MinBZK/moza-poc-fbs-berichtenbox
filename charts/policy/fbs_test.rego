# SPDX-License-Identifier: EUPL-1.2
package main

import rego.v1

container_ok := {
	"name": "dienst",
	"image": "ghcr.io/minbzk/fbs-dienst:main-0000000",
	"livenessProbe": {"httpGet": {"path": "/q/health/live", "port": "http"}},
	"readinessProbe": {"httpGet": {"path": "/q/health/ready", "port": "http"}},
	"startupProbe": {"httpGet": {"path": "/q/health/started", "port": "http"}},
	"resources": {
		"requests": {"cpu": "250m", "memory": "512Mi", "ephemeral-storage": "50Mi"},
		"limits": {"cpu": "1", "memory": "768Mi", "ephemeral-storage": "1Gi"},
	},
	"securityContext": {
		"readOnlyRootFilesystem": true,
		"capabilities": {"drop": ["ALL"]},
		"seccompProfile": {"type": "RuntimeDefault"},
	},
}

deployment(naam, c, extra) := object.union(
	{
		"kind": "Deployment",
		"metadata": {"name": "t", "labels": {"app.kubernetes.io/name": naam}},
		"spec": {
			"replicas": 1,
			"template": {"spec": {"automountServiceAccountToken": false, "containers": [c]}},
		},
	},
	extra,
)

fbs_deny(obj) := {m | some m in deny with input as obj; not mijn_bureau_regel(m)}

# De overgenomen regels van mijn-bureau eisen meer (labels, imagePullPolicy, namespace) dan deze
# minimale objecten bevatten; die meldingen tellen hier niet mee.
mijn_bureau_regel(m) if {
	some fragment in ["imagePullPolicy", "labels", "namespace", "service account", "runAsNonRoot", "as root"]
	contains(m, fragment)
}

test_container_volgens_standaard_slaagt if {
	count(fbs_deny(deployment("berichtenuitvraag", container_ok, {}))) == 0
}

test_ontbrekende_probe_faalt if {
	some probe in ["livenessProbe", "readinessProbe", "startupProbe"]
	c := object.remove(container_ok, [probe])
	fouten := fbs_deny(deployment("berichtenuitvraag", c, {}))
	fouten == {sprintf("Container 'dienst' mist een %s", [probe])}
}

test_elke_probe_afzonderlijk_verplicht if {
	every probe in ["livenessProbe", "readinessProbe", "startupProbe"] {
		c := object.remove(container_ok, [probe])
		count(fbs_deny(deployment("berichtenuitvraag", c, {}))) == 1
	}
}

test_schrijfbaar_root_filesystem_faalt if {
	c := json.patch(container_ok, [{"op": "replace", "path": "/securityContext/readOnlyRootFilesystem", "value": false}])
	fbs_deny(deployment("berichtenuitvraag", c, {})) == {"Container 'dienst' heeft geen read-only root-filesystem"}
}

test_capabilities_niet_allemaal_weg_faalt if {
	c := json.patch(container_ok, [{"op": "replace", "path": "/securityContext/capabilities/drop", "value": ["NET_RAW"]}])
	fbs_deny(deployment("berichtenuitvraag", c, {})) == {"Container 'dienst' laat niet alle capabilities vallen"}
}

test_seccomp_ontbreekt_faalt if {
	c := json.patch(container_ok, [{"op": "remove", "path": "/securityContext/seccompProfile"}])
	fbs_deny(deployment("berichtenuitvraag", c, {})) == {"Container 'dienst' draait zonder seccomp-profiel RuntimeDefault"}
}

test_seccomp_op_podniveau_volstaat if {
	c := json.patch(container_ok, [{"op": "remove", "path": "/securityContext/seccompProfile"}])
	extra := {"spec": {"template": {"spec": {"securityContext": {"seccompProfile": {"type": "RuntimeDefault"}}}}}}
	count(fbs_deny(deployment("berichtenuitvraag", c, extra))) == 0
}

test_ephemeral_storage_ontbreekt_faalt if {
	c := json.patch(container_ok, [{"op": "remove", "path": "/resources/limits/ephemeral-storage"}])
	fbs_deny(deployment("berichtenuitvraag", c, {})) == {"Container 'dienst' mist ephemeral-storage in resources.limits"}
}

test_service_account_token_faalt if {
	d := json.patch(deployment("berichtenuitvraag", container_ok, {}), [{"op": "replace", "path": "/spec/template/spec/automountServiceAccountToken", "value": true}])
	count(fbs_deny(d)) == 1
}

test_service_account_token_afwezig_faalt if {
	d := json.patch(deployment("berichtenuitvraag", container_ok, {}), [{"op": "remove", "path": "/spec/template/spec/automountServiceAccountToken"}])
	count(fbs_deny(d)) == 1
}

stelselregister_ok := deployment("stelselregister", container_ok, {"spec": {"strategy": {"type": "Recreate"}}})

test_stelselregister_een_replica_recreate_slaagt if {
	count(fbs_deny(stelselregister_ok)) == 0
}

test_stelselregister_meer_replicas_faalt if {
	some n in [0, 2, 3]
	d := json.patch(stelselregister_ok, [{"op": "replace", "path": "/spec/replicas", "value": n}])
	fbs_deny(d) == {sprintf("Het stelselregister heeft %v replica's; het draait met precies één", [n])}
}

test_stelselregister_rolling_update_faalt if {
	some strategie in [{"type": "RollingUpdate"}, null]
	d := json.patch(stelselregister_ok, [{"op": "replace", "path": "/spec/strategy", "value": strategie}])
	count(fbs_deny(d)) == 1
}

test_stelselregister_zonder_strategie_faalt if {
	d := json.patch(stelselregister_ok, [{"op": "remove", "path": "/spec/strategy"}])
	count(fbs_deny(d)) == 1
}

test_andere_dienst_mag_meer_replicas if {
	d := json.patch(deployment("berichtenuitvraag", container_ok, {}), [{"op": "replace", "path": "/spec/replicas", "value": 3}])
	count(fbs_deny(d)) == 0
}

test_hpa_op_stelselregister_faalt if {
	hpa := {"kind": "HorizontalPodAutoscaler", "metadata": {"name": "t", "labels": {"app.kubernetes.io/name": "stelselregister"}}}
	fbs_deny(hpa) == {"Het stelselregister mag geen HPA hebben"}
}

test_hpa_op_andere_dienst_mag if {
	hpa := {"kind": "HorizontalPodAutoscaler", "metadata": {"name": "t", "labels": {"app.kubernetes.io/name": "berichtenuitvraag"}}}
	count(fbs_deny(hpa)) == 0
}

networkpolicy(ingress, egress, types) := {
	"kind": "NetworkPolicy",
	"metadata": {"name": "np"},
	"spec": {"policyTypes": types, "ingress": ingress, "egress": egress},
}

dns_egress := {"ports": [{"port": 53, "protocol": "UDP"}]}

gateway_ingress := {"ports": [{"port": 8086}], "from": [{"namespaceSelector": {"matchLabels": {"x": "y"}}}]}

test_afgeschermde_networkpolicy_slaagt if {
	count(fbs_deny(networkpolicy([gateway_ingress], [dns_egress], ["Ingress", "Egress"]))) == 0
}

test_networkpolicy_zonder_regels_slaagt if {
	count(fbs_deny(networkpolicy([], [], ["Ingress", "Egress"]))) == 0
}

test_ingress_zonder_from_faalt if {
	open := {"ports": [{"port": 8086}]}
	fbs_deny(networkpolicy([gateway_ingress, open], [dns_egress], ["Ingress", "Egress"])) == {"NetworkPolicy 'np' laat inkomend verkeer van overal toe"}
}

test_open_egress_faalt if {
	fbs_deny(networkpolicy([gateway_ingress], [dns_egress, {}], ["Ingress", "Egress"])) == {"NetworkPolicy 'np' laat uitgaand verkeer naar overal toe"}
}

test_egress_met_alleen_to_slaagt if {
	naar := {"to": [{"podSelector": {"matchLabels": {"app": "redis"}}}]}
	count(fbs_deny(networkpolicy([gateway_ingress], [naar], ["Ingress", "Egress"]))) == 0
}

test_networkpolicy_zonder_egress_type_faalt if {
	fbs_deny(networkpolicy([gateway_ingress], [], ["Ingress"])) == {"NetworkPolicy 'np' beperkt uitgaand verkeer niet"}
}

test_httproute_zonder_hostnaam_faalt if {
	some spec in [{}, {"hostnames": []}]
	route := {"kind": "HTTPRoute", "metadata": {"name": "r"}, "spec": spec}
	fbs_deny(route) == {"HTTPRoute 'r' heeft geen hostnaam en vangt dan elk verkeer op de Gateway"}
}

test_httproute_met_hostnaam_slaagt if {
	route := {"kind": "HTTPRoute", "metadata": {"name": "r"}, "spec": {"hostnames": ["a.example.nl"]}}
	count(fbs_deny(route)) == 0
}
