# SPDX-License-Identifier: EUPL-1.2
#
# Regels over de demo als geheel: fbs-demo plus de vier releases van fbs-dienst met de values
# onder charts/fbs-demo/stelsel/. Draait met `conftest --combine` over de renders van alle vijf.
# Ze vangen een bedrading die per release klopt maar samen niet: een verkeerd gespeld clientlabel,
# een poort die de aanroeper niet uit mag, een ConfigMap die geen release maakt. Zulke fouten geven
# in het cluster een stille timeout, geen melding.
package demo

import rego.v1

objecten contains o if {
	some bestand in input
	o := bestand.contents
	o.kind
}

deployments contains d if {
	some d in objecten
	d.kind == "Deployment"
}

netpols contains np if {
	some np in objecten
	np.kind == "NetworkPolicy"
}

clientlabel(sleutel) if startswith(sleutel, "client.fbs.minbzk.nl/")

pod_labels(d) := d.spec.template.metadata.labels

geselecteerd(np, labels) if {
	every k, v in np.spec.podSelector.matchLabels {
		labels[k] == v
	}
}

# De poorten waarop een NetworkPolicy pods met dit clientlabel binnenlaat.
binnen_poorten(np, sleutel) := {p.port |
	some regel in object.get(np.spec, "ingress", [])
	some peer in object.get(regel, "from", [])
	object.get(peer, ["podSelector", "matchLabels", sleutel], "") == "true"
	some p in object.get(regel, "ports", [])
}

laat_binnen(sleutel) if {
	some np in netpols
	count(binnen_poorten(np, sleutel)) > 0
}

# De poorten die een pod naar elke bestemming uit mag: egress-regels zonder `to`.
uit_poorten(labels) := {p.port |
	some np in netpols
	geselecteerd(np, labels)
	some regel in object.get(np.spec, "egress", [])
	not regel.to
	some p in object.get(regel, "ports", [])
}

deny contains msg if {
	some d in deployments
	some sleutel, waarde in pod_labels(d)
	clientlabel(sleutel)
	waarde == "true"
	not laat_binnen(sleutel)
	msg := sprintf("Deployment '%s' draagt %s, maar geen NetworkPolicy laat dat label binnen", [d.metadata.name, sleutel])
}

deny contains msg if {
	some d in deployments
	some sleutel, waarde in pod_labels(d)
	clientlabel(sleutel)
	waarde == "true"
	some np in netpols
	some poort in binnen_poorten(np, sleutel)
	not poort in uit_poorten(pod_labels(d))
	msg := sprintf("Deployment '%s' mag %s aanroepen op poort %v, maar zijn NetworkPolicy laat die poort niet uit", [d.metadata.name, np.metadata.name, poort])
}

draagt_label(sleutel) if {
	some d in deployments
	pod_labels(d)[sleutel] == "true"
}

deny contains msg if {
	some np in netpols
	# Alleen de NetworkPolicies van de demo zelf: een stelseldienst laat zijn clientlabel ook toe
	# als niemand in de demo hem aanroept (het stelselregister).
	np.metadata.labels["app.kubernetes.io/part-of"] == "fbs-demo"
	some regel in object.get(np.spec, "ingress", [])
	some peer in object.get(regel, "from", [])
	some sleutel, _ in object.get(peer, ["podSelector", "matchLabels"], {})
	clientlabel(sleutel)
	not draagt_label(sleutel)
	msg := sprintf("NetworkPolicy '%s' laat %s binnen, maar geen pod draagt dat label", [np.metadata.name, sleutel])
}

configmap(naam) if {
	some o in objecten
	o.kind == "ConfigMap"
	o.metadata.name == naam
}

deny contains msg if {
	some d in deployments
	some v in object.get(d.spec.template.spec, "volumes", [])
	naam := v.configMap.name
	not configmap(naam)
	msg := sprintf("Deployment '%s' mount ConfigMap '%s', maar geen release maakt die", [d.metadata.name, naam])
}

# --- Via de Gateway ----------------------------------------------------------------------------
# De diensten roepen elkaar over https via de Gateway aan. Een hostnaam zonder route, of een route
# waarvan de NetworkPolicy de Gateway niet binnenlaat, geeft in het cluster een stille timeout.

routes contains r if {
	some r in objecten
	r.kind == "HTTPRoute"
}

route_hosts contains h if {
	some r in routes
	some h in r.spec.hostnames
}

# Het domein van een hostnaam: alles na het eerste label.
domein(host) := concat(".", array.slice(delen, 1, count(delen))) if {
	delen := split(host, ".")
}

route_domeinen contains domein(h) if some h in route_hosts

onder_routedomein(host) if {
	some d in route_domeinen
	endswith(host, concat("", [".", d]))
}

env_namen(d) := {e.name |
	some c in d.spec.template.spec.containers
	some e in object.get(c, "env", [])
}

env_waarden(d) := {[e.name, e.value] |
	some c in d.spec.template.spec.containers
	some e in object.get(c, "env", [])
	is_string(e.value)
}

deny contains msg if {
	some d in deployments
	some [naam, waarde] in env_waarden(d)
	host := regex.find_all_string_submatch_n(`^https://([^/:]+)`, waarde, 1)[0][1]
	onder_routedomein(host)
	not host in route_hosts
	msg := sprintf("Deployment '%s' zet %s op %s, maar geen HTTPRoute heeft hostnaam %s", [d.metadata.name, naam, waarde, host])
}

services contains s if {
	some s in objecten
	s.kind == "Service"
}

# De containerpoort achter een Service-poort: targetPort bij naam of nummer, of de poort zelf.
containerpoort(svc, p, d) := nummer if {
	doel := object.get(p, "targetPort", p.port)
	is_number(doel)
	nummer := doel
} else := nummer if {
	doel := p.targetPort
	is_string(doel)
	some c in d.spec.template.spec.containers
	some cp in c.ports
	cp.name == doel
	nummer := cp.containerPort
}

geselecteerd_door_service(svc, d) if {
	every k, v in svc.spec.selector {
		pod_labels(d)[k] == v
	}
}

# Een ingress-regel met een namespaceSelector: de proxy's van de Gateway draaien in een andere
# namespace dan de release.
gateway_poorten(labels) := {p.port |
	some np in netpols
	geselecteerd(np, labels)
	some regel in object.get(np.spec, "ingress", [])
	some peer in object.get(regel, "from", [])
	peer.namespaceSelector
	some p in object.get(regel, "ports", [])
}

# Alle releases laten de proxy's van dezelfde Gateway binnen. Een afwijkende namespace laat in het
# cluster elke route van die release in een timeout lopen.
gateway_namespaces contains json.marshal(peer.namespaceSelector.matchLabels) if {
	some np in netpols
	some regel in object.get(np.spec, "ingress", [])
	some peer in object.get(regel, "from", [])
	peer.namespaceSelector
}

deny contains msg if {
	count(gateway_namespaces) > 1
	msg := sprintf("De NetworkPolicies laten de Gateway uit verschillende namespaces binnen: %v", [sort(gateway_namespaces)])
}

deny contains msg if {
	some r in routes
	some regel in r.spec.rules
	some ref in regel.backendRefs
	some svc in services
	svc.metadata.name == ref.name
	some p in svc.spec.ports
	p.port == ref.port
	some d in deployments
	geselecteerd_door_service(svc, d)
	poort := containerpoort(svc, p, d)
	not poort in gateway_poorten(pod_labels(d))
	msg := sprintf("HTTPRoute '%s' wijst naar %s:%v, maar de NetworkPolicy van '%s' laat de Gateway niet binnen op poort %v", [r.metadata.name, ref.name, ref.port, d.metadata.name, poort])
}

# --- Paneel en magazijnen ----------------------------------------------------------------------
# Het paneel leegt een magazijn rechtstreeks in zijn schema. Een ander schema dan dat van het
# magazijn leegt stil een leeg schema.
paneel_schema := {"MAGAZIJN_A_DB_SCHEMA": "berichtenmagazijn-a", "MAGAZIJN_B_DB_SCHEMA": "berichtenmagazijn-b"}

env_van(naam, sleutel) := waarde if {
	some d in deployments
	d.metadata.name == naam
	some paar in env_waarden(d)
	paar[0] == sleutel
	waarde := paar[1]
}

deny contains msg if {
	some d in deployments
	some [sleutel, schema] in env_waarden(d)
	magazijn := paneel_schema[sleutel]
	eigen := env_van(magazijn, "DB_SCHEMA")
	schema != eigen
	msg := sprintf("Deployment '%s' leegt schema %s van %s, maar dat magazijn gebruikt %s", [d.metadata.name, schema, magazijn, eigen])
}

deny contains msg if {
	some d in deployments
	some [sleutel, schema] in env_waarden(d)
	magazijn := paneel_schema[sleutel]
	not env_van(magazijn, "DB_SCHEMA")
	msg := sprintf("Deployment '%s' leegt schema %s van %s, maar er is geen Deployment %s met DB_SCHEMA", [d.metadata.name, schema, magazijn, magazijn])
}

# --- Onveilige kleppen -------------------------------------------------------------------------
# fbs-dienst kent één schakelaar voor alle kleppen. De demo heeft er één nodig, Redis zonder TLS;
# een andere, zoals het logboek zonder TLS, hoort ook in de demo niet.
deny contains msg if {
	some d in deployments
	some naam in env_namen(d)
	contains(upper(naam), "UNSAFE")
	naam != "FBS_REDIS_UNSAFE_ALLOW_PLAINTEXT"
	msg := sprintf("Deployment '%s' zet %s; de demo laat alleen FBS_REDIS_UNSAFE_ALLOW_PLAINTEXT toe", [d.metadata.name, naam])
}

# --- Wat de routes naar buiten zetten ----------------------------------------------------------

route_naam(r) := r.metadata.labels["app.kubernetes.io/name"]

# De simulator: alleen wat de uitvraag doet. Een match zonder method laat elke methode door, ook
# aanleveren.
simulator_methoden := {"GET", "PATCH", "DELETE"}

deny contains msg if {
	some r in routes
	route_naam(r) == "simulator"
	some regel in r.spec.rules
	some match in regel.matches
	not object.get(match, "method", "") in simulator_methoden
	msg := sprintf("HTTPRoute '%s' laat methode '%s' door; alleen GET, PATCH en DELETE", [r.metadata.name, object.get(match, "method", "elke")])
}

deny contains msg if {
	some r in routes
	route_naam(r) == "simulator"
	methoden := {m.method | some regel in r.spec.rules; some m in regel.matches; m.method}
	ontbrekend := simulator_methoden - methoden
	count(ontbrekend) > 0
	msg := sprintf("HTTPRoute '%s' mist methode(n) %v; de uitvraag gebruikt ze", [r.metadata.name, sort(ontbrekend)])
}

# De magazijnen: alleen het lezen door de uitvraag en het OpenAPI-document. Aanleveren gaat binnen
# het cluster, via het paneel.
magazijn_paden := {"/api/v1/berichten", "/openapi.json"}

deny contains msg if {
	some r in routes
	route_naam(r) == "berichtenmagazijn"
	some regel in r.spec.rules
	some match in regel.matches
	not match.path.value in magazijn_paden
	msg := sprintf("HTTPRoute '%s' laat %s door; alleen %v", [r.metadata.name, match.path.value, sort(magazijn_paden)])
}

# De streams van de uitvraag lopen tot een uur; elke andere regel heeft een grens.
stream_match(match) if {
	match.path.type == "Exact"
	regex.match(`/_(ophalen|volgen)$`, match.path.value)
}

deny contains msg if {
	some r in routes
	some regel in r.spec.rules
	some match in regel.matches
	stream_match(match)
	object.get(regel, ["timeouts", "request"], "") != "0s"
	msg := sprintf("HTTPRoute '%s': stream %s zonder timeouts.request 0s; Envoy breekt hem anders af", [r.metadata.name, match.path.value])
}

deny contains msg if {
	some r in routes
	some regel in r.spec.rules
	object.get(regel, ["timeouts", "request"], "") == "0s"
	some match in regel.matches
	not stream_match(match)
	msg := sprintf("HTTPRoute '%s': %v zonder grens op de request-timeout; alleen de streams", [r.metadata.name, object.get(match, ["path", "value"], "?")])
}
