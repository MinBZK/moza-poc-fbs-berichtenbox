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

# Alleen de NetworkPolicies van de demo zelf: een stelseldienst laat zijn clientlabel ook toe als
# niemand in de demo hem aanroept (het stelselregister).
draagt_label(sleutel) if {
	some d in deployments
	pod_labels(d)[sleutel] == "true"
}

deny contains msg if {
	some np in netpols
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
