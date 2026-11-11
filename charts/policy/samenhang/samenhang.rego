# SPDX-License-Identifier: EUPL-1.2
#
# Regels over de samenhang tussen de manifesten van één release. Draait met `conftest --combine`:
# de invoer is dan een lijst van {path, contents}.
package samenhang

import rego.v1

objecten contains o if {
	some bestand in input
	o := bestand.contents
	o.kind
}

dekt(selector, labels) if {
	every k, v in selector.matchLabels {
		labels[k] == v
	}
}

# Zonder NetworkPolicy staat de pod open naar en van het hele cluster.
deny contains msg if {
	some d in objecten
	d.kind == "Deployment"
	pod_labels := d.spec.template.metadata.labels
	not afgeschermd(pod_labels)
	msg := sprintf("Deployment '%s' valt onder geen enkele NetworkPolicy", [d.metadata.name])
}

afgeschermd(pod_labels) if {
	some np in objecten
	np.kind == "NetworkPolicy"
	dekt(np.spec.podSelector, pod_labels)
}

deny contains msg if {
	some d in objecten
	d.kind == "Deployment"
	pod_labels := d.spec.template.metadata.labels
	not budget(pod_labels)
	msg := sprintf("Deployment '%s' heeft geen PodDisruptionBudget", [d.metadata.name])
}

budget(pod_labels) if {
	some pdb in objecten
	pdb.kind == "PodDisruptionBudget"
	dekt(pdb.spec.selector, pod_labels)
}

deny contains msg if {
	some d in objecten
	d.kind == "Deployment"
	sa := d.spec.template.spec.serviceAccountName
	not service_account(sa)
	msg := sprintf("Deployment '%s' verwijst naar service-account '%s', dat de chart niet aanmaakt", [d.metadata.name, sa])
}

service_account(naam) if {
	some sa in objecten
	sa.kind == "ServiceAccount"
	sa.metadata.name == naam
}

# Een route naar een Service die niet bestaat of de verkeerde poort noemt, levert pas in het
# cluster een 500 op.
deny contains msg if {
	some r in objecten
	r.kind == "HTTPRoute"
	some regel in r.spec.rules
	some ref in regel.backendRefs
	not service_poort(ref.name, ref.port)
	msg := sprintf("HTTPRoute '%s' wijst naar %s:%v, maar die Service-poort bestaat niet", [r.metadata.name, ref.name, ref.port])
}

service_poort(naam, poort) if {
	some s in objecten
	s.kind == "Service"
	s.metadata.name == naam
	some p in s.spec.ports
	p.port == poort
}
