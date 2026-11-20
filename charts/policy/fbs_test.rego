# SPDX-License-Identifier: EUPL-1.2
#
# Varianten staan in `every`, niet in `some`: een test met `some` slaagt al als één variant het
# verwachte oplevert.
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
		"allowPrivilegeEscalation": false,
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

uitvraag(c) := deployment("berichtenuitvraag", c, {})

patch(obj, op) := json.patch(obj, [op])

fbs_deny(obj) := {m | some m in deny with input as obj; not mijn_bureau_regel(m)}

# De overgenomen regels van mijn-bureau eisen meer (labels, imagePullPolicy, namespace) dan deze
# minimale objecten bevatten; die meldingen tellen hier niet mee.
mijn_bureau_regel(m) if {
	some fragment in ["imagePullPolicy", "labels", "does not have a namespace", "service account", "runAsNonRoot", "as root"]
	contains(m, fragment)
}

test_container_volgens_standaard_slaagt if {
	count(fbs_deny(uitvraag(container_ok))) == 0
}

test_elke_probe_afzonderlijk_verplicht if {
	every probe in ["livenessProbe", "readinessProbe", "startupProbe"] {
		fbs_deny(uitvraag(object.remove(container_ok, [probe]))) == {sprintf("Container 'dienst' mist een %s", [probe])}
	}
}

test_initcontainer_hoeft_geen_probes if {
	init := object.remove(container_ok, ["livenessProbe", "readinessProbe", "startupProbe"])
	d := patch(uitvraag(container_ok), {"op": "add", "path": "/spec/template/spec/initContainers", "value": [init]})
	count(fbs_deny(d)) == 0
}

test_schrijfbaar_root_filesystem_faalt if {
	every waarde in [false, null] {
		c := patch(container_ok, {"op": "replace", "path": "/securityContext/readOnlyRootFilesystem", "value": waarde})
		fbs_deny(uitvraag(c)) == {"Container 'dienst' heeft geen read-only root-filesystem"}
	}
}

test_initcontainer_valt_onder_dezelfde_regels if {
	init := patch(container_ok, {"op": "replace", "path": "/securityContext/readOnlyRootFilesystem", "value": false})
	d := patch(uitvraag(container_ok), {"op": "add", "path": "/spec/template/spec/initContainers", "value": [init]})
	fbs_deny(d) == {"Container 'dienst' heeft geen read-only root-filesystem"}
}

# Pod Security restricted: afwezig of null telt als toestaan, ook in een initContainer.
test_privilege_escalation_niet_uit_faalt if {
	every waarde in [true, null] {
		c := patch(container_ok, {"op": "replace", "path": "/securityContext/allowPrivilegeEscalation", "value": waarde})
		"Container 'dienst' zet allowPrivilegeEscalation niet op false; Pod Security restricted weigert de pod" in fbs_deny(uitvraag(c))
	}
}

test_privilege_escalation_ontbreekt_in_initcontainer_faalt if {
	init := object.remove(container_ok, ["livenessProbe", "readinessProbe", "startupProbe"])
	init_open := json.patch(init, [{"op": "replace", "path": "/name", "value": "init"}, {"op": "remove", "path": "/securityContext/allowPrivilegeEscalation"}])
	d := patch(uitvraag(container_ok), {"op": "add", "path": "/spec/template/spec/initContainers", "value": [init_open]})
	fbs_deny(d) == {"Container 'init' zet allowPrivilegeEscalation niet op false; Pod Security restricted weigert de pod"}
}

test_hostport_faalt if {
	c := patch(container_ok, {"op": "add", "path": "/ports", "value": [{"containerPort": 8086}, {"containerPort": 8080, "hostPort": 8080}]})
	fbs_deny(uitvraag(c)) == {"Container 'dienst' gebruikt hostPort 8080; Pod Security restricted weigert de pod"}
}

test_containerpoort_zonder_hostport_slaagt if {
	c := patch(container_ok, {"op": "add", "path": "/ports", "value": [{"containerPort": 8086, "name": "http"}]})
	count(fbs_deny(uitvraag(c))) == 0
}

met_volumes(volumes) := patch(uitvraag(container_ok), {"op": "add", "path": "/spec/template/spec/volumes", "value": volumes})

test_toegestane_volumes_slagen if {
	every volumes in [
		[],
		[{"name": "tmp", "emptyDir": {"sizeLimit": "256Mi"}}],
		[{"name": "tmp", "emptyDir": {}}, {"name": "keystore", "secret": {"secretName": "k"}}, {"name": "c", "configMap": {"name": "c"}}],
	] {
		count(fbs_deny(met_volumes(volumes))) == 0
	}
}

test_andere_volumesoort_faalt if {
	every geval in [["hostPath", {"path": "/"}], ["nfs", {"server": "n", "path": "/"}], ["gitRepo", {"repository": "x"}]] {
		volumes := [{"name": "tmp", "emptyDir": {}}, {"name": "v", geval[0]: geval[1]}]
		sprintf("Volume 'v' is van soort %s; Pod Security restricted weigert de pod", [geval[0]]) in fbs_deny(met_volumes(volumes))
	}
}

test_soort_buiten_de_namespace_faalt if {
	every soort in ["ClusterRole", "ClusterRoleBinding", "Namespace", "Role", "CustomResourceDefinition", "Job"] {
		obj := {"kind": soort, "metadata": {"name": "x"}}
		fbs_deny(obj) == {sprintf("%s 'x' hoort niet in deze chart: alleen objecten binnen de eigen namespace die de rol admin mag maken", [soort])}
	}
}

test_soorten_van_de_chart_slagen if {
	every soort in ["ConfigMap", "Secret", "Service", "ServiceAccount", "PodDisruptionBudget"] {
		count(fbs_deny({"kind": soort, "metadata": {"name": "x"}})) == 0
	}
}

test_capabilities_niet_allemaal_weg_faalt if {
	every drop in [["NET_RAW"], [], null] {
		c := patch(container_ok, {"op": "replace", "path": "/securityContext/capabilities/drop", "value": drop})
		fbs_deny(uitvraag(c)) == {"Container 'dienst' laat niet alle capabilities vallen"}
	}
}

test_capabilities_toevoegen_faalt if {
	c := patch(container_ok, {"op": "add", "path": "/securityContext/capabilities/add", "value": ["NET_ADMIN"]})
	fbs_deny(uitvraag(c)) == {"Container 'dienst' voegt capabilities toe"}
}

test_lege_add_lijst_slaagt if {
	every add in [[], null] {
		c := patch(container_ok, {"op": "add", "path": "/securityContext/capabilities/add", "value": add})
		count(fbs_deny(uitvraag(c))) == 0
	}
}

pod_seccomp(type) := {"spec": {"template": {"spec": {"securityContext": {"seccompProfile": {"type": type}}}}}}

test_seccomp_ontbreekt_faalt if {
	c := patch(container_ok, {"op": "remove", "path": "/securityContext/seccompProfile"})
	fbs_deny(uitvraag(c)) == {"Container 'dienst' draait zonder seccomp-profiel RuntimeDefault"}
}

test_seccomp_op_podniveau_volstaat if {
	c := patch(container_ok, {"op": "remove", "path": "/securityContext/seccompProfile"})
	count(fbs_deny(deployment("berichtenuitvraag", c, pod_seccomp("RuntimeDefault")))) == 0
}

test_seccomp_container_gaat_voor_pod if {
	c := patch(container_ok, {"op": "replace", "path": "/securityContext/seccompProfile/type", "value": "Unconfined"})
	fbs_deny(deployment("berichtenuitvraag", c, pod_seccomp("RuntimeDefault"))) == {"Container 'dienst' draait zonder seccomp-profiel RuntimeDefault"}
}

test_seccomp_container_redt_open_pod if {
	count(fbs_deny(deployment("berichtenuitvraag", container_ok, pod_seccomp("Unconfined")))) == 0
}

test_ephemeral_storage_ontbreekt_faalt if {
	every soort in ["requests", "limits"] {
		c := patch(container_ok, {"op": "remove", "path": sprintf("/resources/%s/ephemeral-storage", [soort])})
		fbs_deny(uitvraag(c)) == {sprintf("Container 'dienst' mist ephemeral-storage in resources.%s", [soort])}
	}
}

test_service_account_token_faalt if {
	every op in [
		{"op": "replace", "path": "/spec/template/spec/automountServiceAccountToken", "value": true},
		{"op": "replace", "path": "/spec/template/spec/automountServiceAccountToken", "value": null},
		{"op": "remove", "path": "/spec/template/spec/automountServiceAccountToken"},
	] {
		fbs_deny(patch(uitvraag(container_ok), op)) == {"De pod krijgt een service-account-token; de diensten praten niet met de Kubernetes-API"}
	}
}

stelselregister_ok := deployment("stelselregister", container_ok, {"spec": {"strategy": {"type": "Recreate"}}})

test_stelselregister_een_replica_recreate_slaagt if {
	count(fbs_deny(stelselregister_ok)) == 0
}

test_stelselregister_ander_aantal_replicas_faalt if {
	every n in [0, 2, 3] {
		d := patch(stelselregister_ok, {"op": "replace", "path": "/spec/replicas", "value": n})
		fbs_deny(d) == {sprintf("Het stelselregister heeft %v replica's; het draait met precies één", [n])}
	}
}

test_stelselregister_zonder_recreate_faalt if {
	every op in [
		{"op": "replace", "path": "/spec/strategy", "value": {"type": "RollingUpdate"}},
		{"op": "replace", "path": "/spec/strategy", "value": null},
		{"op": "remove", "path": "/spec/strategy"},
	] {
		fbs_deny(patch(stelselregister_ok, op)) == {"Het stelselregister rolt niet uit met Recreate; tijdens de uitrol draaien er dan twee"}
	}
}

test_andere_dienst_mag_meer_replicas if {
	d := patch(uitvraag(container_ok), {"op": "replace", "path": "/spec/replicas", "value": 3})
	count(fbs_deny(d)) == 0
}

hpa(naam) := {"kind": "HorizontalPodAutoscaler", "metadata": {"name": "t", "labels": {"app.kubernetes.io/name": naam}}}

test_hpa_op_stelselregister_faalt if {
	fbs_deny(hpa("stelselregister")) == {"Het stelselregister mag geen HPA hebben"}
}

test_hpa_op_andere_dienst_mag if {
	count(fbs_deny(hpa("berichtenuitvraag"))) == 0
}

networkpolicy(ingress, egress, types) := {
	"kind": "NetworkPolicy",
	"metadata": {"name": "np"},
	"spec": {"policyTypes": types, "ingress": ingress, "egress": egress},
}

dns_egress := {"ports": [{"port": 53, "protocol": "UDP"}]}

gateway_bron := {"namespaceSelector": {"matchLabels": {"x": "y"}}}

gateway_ingress := {"ports": [{"port": 8086}], "from": [gateway_bron]}

test_afgeschermde_networkpolicy_slaagt if {
	count(fbs_deny(networkpolicy([gateway_ingress], [dns_egress], ["Ingress", "Egress"]))) == 0
}

test_networkpolicy_zonder_regels_slaagt if {
	count(fbs_deny(networkpolicy([], [], ["Ingress", "Egress"]))) == 0
}

test_ingress_zonder_bronnen_faalt if {
	every regel in [
		{"ports": [{"port": 8086}]},
		{"ports": [{"port": 8086}], "from": null},
		{"ports": [{"port": 8086}], "from": []},
		{},
	] {
		fbs_deny(networkpolicy([gateway_ingress, regel], [dns_egress], ["Ingress", "Egress"])) == {"NetworkPolicy 'np' laat inkomend verkeer van overal toe"}
	}
}

test_bron_zonder_beperking_faalt if {
	every peer in [
		{"namespaceSelector": {}},
		{"podSelector": {}},
		{"namespaceSelector": {"matchLabels": {}}, "podSelector": {}},
		{"namespaceSelector": {"matchLabels": null}},
		{"podSelector": null, "namespaceSelector": {}},
		{"namespaceSelector": {"matchLabels": {}, "matchExpressions": []}},
		{"ipBlock": {"cidr": "0.0.0.0/0"}},
		{"ipBlock": {"cidr": "::/0"}},
		{},
	] {
		regel := {"ports": [{"port": 8086}], "from": [peer]}
		fbs_deny(networkpolicy([regel], [dns_egress], ["Ingress", "Egress"])) == {"NetworkPolicy 'np' heeft een bron zonder beperking; die laat alles toe"}
	}
}

test_korte_prefix_is_zonder_beperking if {
	every blok in [
		{"cidr": "0.0.0.0/1"},
		{"cidr": "128.0.0.0/1"},
		{"cidr": "10.0.0.0/7"},
		{"cidr": "0.0.0.0/0", "except": ["10.0.0.0/8"]},
		{"cidr": "0.0.0.0/0", "except": ["10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"]},
	] {
		regel := {"ports": [{"port": 8086}], "from": [{"ipBlock": blok}]}
		fbs_deny(networkpolicy([regel], [dns_egress], ["Ingress", "Egress"])) == {"NetworkPolicy 'np' heeft een bron zonder beperking; die laat alles toe"}
	}
}

test_bron_met_beperking_slaagt if {
	every peer in [
		{"namespaceSelector": {"matchLabels": {"a": "b"}}, "podSelector": {}},
		{"podSelector": {"matchExpressions": [{"key": "a", "operator": "Exists"}]}},
		{"ipBlock": {"cidr": "10.0.0.0/8"}},
		{"ipBlock": {"cidr": "10.0.0.0/8", "except": ["10.1.0.0/16"]}},
	] {
		regel := {"ports": [{"port": 8086}], "from": [peer]}
		count(fbs_deny(networkpolicy([regel], [dns_egress], ["Ingress", "Egress"]))) == 0
	}
}

test_bestemming_zonder_beperking_faalt if {
	every peer in [{"namespaceSelector": {}}, {"podSelector": null}, {"ipBlock": {"cidr": "0.0.0.0/0"}}] {
		regel := {"to": [peer], "ports": [{"port": 5432}]}
		fbs_deny(networkpolicy([gateway_ingress], [regel], ["Ingress", "Egress"])) == {"NetworkPolicy 'np' heeft een bestemming zonder beperking; die laat alles toe"}
	}
}

test_gateway_poort_naar_overal_faalt if {
	every poort in [10080, 10443] {
		regel := {"ports": [{"port": poort, "protocol": "TCP"}]}
		fbs_deny(networkpolicy([gateway_ingress], [regel], ["Ingress", "Egress"])) == {sprintf("NetworkPolicy 'np' laat poort %v verder toe dan de Gateway-proxy's", [poort])}
	}
}

proxys := {"namespaceSelector": {"matchLabels": {"a": "b"}}, "podSelector": {"matchLabels": {"c": "d"}}}

# Ook één ruime peer naast de proxy's is te ruim.
test_gateway_poort_naar_hele_namespace_faalt if {
	every naar in [
		[{"namespaceSelector": {"matchLabels": {"a": "b"}}}],
		[{"namespaceSelector": {"matchLabels": {"a": "b"}}, "podSelector": {}}],
		[proxys, {"namespaceSelector": {"matchLabels": {"a": "b"}}}],
		[{"namespaceSelector": {"matchLabels": {"a": "b"}}}, proxys],
	] {
		regel := {"to": naar, "ports": [{"port": 10443, "protocol": "TCP"}]}
		fbs_deny(networkpolicy([gateway_ingress], [regel], ["Ingress", "Egress"])) == {"NetworkPolicy 'np' laat poort 10443 verder toe dan de Gateway-proxy's"}
	}
}

test_poortbereik_over_gateway_poort_faalt if {
	naar := {"namespaceSelector": {"matchLabels": {"a": "b"}}}
	every geval in [[10000, 10500], [10081, 10443], [10443, 10443], [10080, 10080]] {
		regel := {"to": [naar], "ports": [{"port": geval[0], "endPort": geval[1], "protocol": "TCP"}]}
		fbs_deny(networkpolicy([gateway_ingress], [regel], ["Ingress", "Egress"])) == {sprintf("NetworkPolicy 'np' laat poorten %v-%v verder toe dan de Gateway-proxy's", geval)}
	}
}

test_poortbereik_naast_gateway_poort_mag if {
	naar := {"namespaceSelector": {"matchLabels": {"a": "b"}}}
	every geval in [[10000, 10079], [10081, 10442], [10444, 11000]] {
		regel := {"to": [naar], "ports": [{"port": geval[0], "endPort": geval[1], "protocol": "TCP"}]}
		count(fbs_deny(networkpolicy([gateway_ingress], [regel], ["Ingress", "Egress"]))) == 0
	}
}

test_poort_zonder_nummer_naar_namespace_faalt if {
	naar := {"namespaceSelector": {"matchLabels": {"a": "b"}}}
	every protocol in ["TCP", "UDP"] {
		regel := {"to": [naar], "ports": [{"protocol": protocol}]}
		fbs_deny(networkpolicy([gateway_ingress], [regel], ["Ingress", "Egress"])) == {"NetworkPolicy 'np' laat alle poorten verder toe dan de Gateway-proxy's"}
	}
}

test_poort_zonder_nummer_naar_overal_faalt if {
	every protocol in ["TCP", "UDP"] {
		regel := {"ports": [{"protocol": protocol}]}
		fbs_deny(networkpolicy([gateway_ingress], [regel], ["Ingress", "Egress"])) == {
			"NetworkPolicy 'np' laat alle poorten naar overal toe",
			"NetworkPolicy 'np' laat alle poorten verder toe dan de Gateway-proxy's",
		}
	}
}

# Elk bereik zonder bestemming, ook een klein: de chart rendert er zelf geen.
test_poortbereik_naar_overal_faalt if {
	every geval in [[1, 9000], [1, 1023], [5432, 5433]] {
		regel := {"ports": [{"port": geval[0], "endPort": geval[1], "protocol": "TCP"}]}
		fbs_deny(networkpolicy([gateway_ingress], [regel], ["Ingress", "Egress"])) == {sprintf("NetworkPolicy 'np' laat poorten %v-%v naar overal toe", geval)}
	}
}

test_losse_poorten_naar_overal_mogen if {
	every poorten in [[{"port": 443}], [{"port": 443}, {"port": 5432, "protocol": "TCP"}]] {
		regel := {"ports": poorten}
		count(fbs_deny(networkpolicy([gateway_ingress], [regel], ["Ingress", "Egress"]))) == 0
	}
}

test_gateway_poort_naar_proxys_slaagt if {
	naar := {"to": [{"namespaceSelector": {"matchLabels": {"a": "b"}}, "podSelector": {"matchLabels": {"c": "d"}}}]}
	regel := object.union(naar, {"ports": [{"port": 10443, "protocol": "TCP"}]})
	count(fbs_deny(networkpolicy([gateway_ingress], [regel], ["Ingress", "Egress"]))) == 0
}

test_open_egress_faalt if {
	every regel in [{}, {"to": []}, {"to": null, "ports": []}, {"ports": null}] {
		fbs_deny(networkpolicy([gateway_ingress], [dns_egress, regel], ["Ingress", "Egress"])) == {"NetworkPolicy 'np' laat uitgaand verkeer naar overal toe"}
	}
}

test_egress_met_to_of_ports_slaagt if {
	every regel in [{"to": [{"podSelector": {"matchLabels": {"app": "redis"}}}]}, dns_egress] {
		count(fbs_deny(networkpolicy([gateway_ingress], [regel], ["Ingress", "Egress"]))) == 0
	}
}

test_networkpolicy_zonder_egress_type_faalt if {
	fbs_deny(networkpolicy([gateway_ingress], [], ["Ingress"])) == {"NetworkPolicy 'np' beperkt uitgaand verkeer niet"}
}

gw_ref := {"name": "gw", "sectionName": "https"}

route(spec) := {"kind": "HTTPRoute", "metadata": {"name": "r"}, "spec": object.union({"parentRefs": [gw_ref]}, spec)}

test_httproute_zonder_sectionname_faalt if {
	every refs in [[{"name": "gw"}], [{"name": "gw", "sectionName": ""}], [gw_ref, {"name": "gw"}]] {
		spec := {"parentRefs": refs, "hostnames": ["a.example.nl"], "rules": [api_regel]}
		fbs_deny(route(spec)) == {"HTTPRoute 'r' hangt aan Gateway 'gw' zonder sectionName, dus ook aan een listener zonder TLS"}
	}
}

test_httproute_zonder_parentref_faalt if {
	every refs in [[], null] {
		spec := {"parentRefs": refs, "hostnames": ["a.example.nl"], "rules": [api_regel]}
		fbs_deny(route(spec)) == {"HTTPRoute 'r' heeft geen parentRef"}
	}
}

test_httproute_met_twee_listeners_slaagt if {
	spec := {"parentRefs": [gw_ref, {"name": "gw", "sectionName": "https-2"}], "hostnames": ["a.example.nl"], "rules": [api_regel]}
	count(fbs_deny(route(spec))) == 0
}

api_regel := {"matches": [{"path": {"type": "PathPrefix", "value": "/api/v1"}}]}

test_httproute_zonder_hostnaam_faalt if {
	every spec in [{"rules": [api_regel]}, {"hostnames": [], "rules": [api_regel]}, {"hostnames": null, "rules": [api_regel]}] {
		fbs_deny(route(spec)) == {"HTTPRoute 'r' heeft geen hostnaam en vangt dan elk verkeer op de Gateway"}
	}
}

test_httproute_met_hostnaam_en_api_pad_slaagt if {
	count(fbs_deny(route({"hostnames": ["a.example.nl"], "rules": [api_regel]}))) == 0
}

test_httproute_naar_q_of_alles_faalt if {
	every pad in [
		{"type": "PathPrefix", "value": "/"},
		{"type": "PathPrefix", "value": "/q"},
		{"type": "Exact", "value": "/q/health/ready"},
		{"type": "PathPrefix", "value": "/q/swagger-ui"},
	] {
		spec := {"hostnames": ["a.example.nl"], "rules": [{"matches": [{"path": pad}]}]}
		fbs_deny(route(spec)) == {sprintf("HTTPRoute 'r' zet %s open; /q hoort binnen het cluster", [pad.value])}
	}
}

test_httproute_ander_padtype_of_geen_pad_faalt if {
	every match in [
		{"path": {"type": "RegularExpression", "value": "/.*"}},
		{"headers": [{"name": "x", "value": "y"}]},
		{},
	] {
		spec := {"hostnames": ["a.example.nl"], "rules": [api_regel, {"matches": [match]}]}
		fbs_deny(route(spec)) == {"HTTPRoute 'r' heeft een match zonder pad van type PathPrefix of Exact"}
	}
}

test_httproute_exact_root_mag if {
	spec := {"hostnames": ["a.example.nl"], "rules": [{"matches": [{"path": {"type": "Exact", "value": "/"}}]}]}
	count(fbs_deny(route(spec))) == 0
}

test_httproute_regel_zonder_matches_faalt if {
	every regel in [{}, {"matches": []}] {
		spec := {"hostnames": ["a.example.nl"], "rules": [api_regel, regel]}
		fbs_deny(route(spec)) == {"HTTPRoute 'r' heeft een regel zonder matches; die zet alles open"}
	}
}

ingress_obj(pad, type) := {"kind": "Ingress", "metadata": {"name": "i"}, "spec": {"rules": [{"http": {"paths": [{"path": pad, "pathType": type}]}}]}}

test_ingress_naar_q_of_alles_faalt if {
	every geval in [["/", "Prefix"], ["/q", "Prefix"], ["/q/health/ready", "Exact"], ["/q/swagger-ui", "Prefix"]] {
		fbs_deny(ingress_obj(geval[0], geval[1])) == {sprintf("Ingress 'i' zet %s open; /q hoort binnen het cluster", [geval[0]])}
	}
}

test_ingress_ander_pathtype_faalt if {
	every type in ["ImplementationSpecific", "PathPrefix"] {
		fbs_deny(ingress_obj("/api/v1", type)) == {sprintf("Ingress 'i' heeft pad /api/v1 met pathType %s; alleen Prefix of Exact", [type])}
	}
}

test_ingress_naar_api_slaagt if {
	count(fbs_deny(ingress_obj("/api/v1", "Prefix"))) == 0
}
