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

# Een Ingress-backend noemt de poort bij naam of bij nummer.
service_poort(naam, poort) if {
	some s in objecten
	s.kind == "Service"
	s.metadata.name == naam
	some p in s.spec.ports
	p.name == poort
}

deny contains msg if {
	some i in objecten
	i.kind == "Ingress"
	some regel in i.spec.rules
	some pad in regel.http.paths
	svc := pad.backend.service
	poort := object.get(svc.port, "number", object.get(svc.port, "name", ""))
	not service_poort(svc.name, poort)
	msg := sprintf("Ingress '%s' wijst naar %s:%v, maar die Service-poort bestaat niet", [i.metadata.name, svc.name, poort])
}

deployments := [d | some d in objecten; d.kind == "Deployment"]

# Een Service zonder pods levert 503's op, terwijl de render en de pods zelf in orde lijken.
deny contains msg if {
	some s in objecten
	s.kind == "Service"
	count(deployments) > 0
	not selecteert_pods(s)
	msg := sprintf("Service '%s' selecteert geen pods van de release", [s.metadata.name])
}

selecteert_pods(s) if {
	some d in deployments
	geselecteerd(s, d)
}

# Een targetPort moet een containerpoort zijn van de pods die de Service selecteert: bij naam of
# bij nummer.
deny contains msg if {
	some s in objecten
	s.kind == "Service"
	some p in s.spec.ports
	doel := object.get(p, "targetPort", p.port)
	not containerpoort(s, doel)
	msg := sprintf("Service '%s' wijst naar targetPort %v, maar geen geselecteerde container heeft die poort", [s.metadata.name, doel])
}

geselecteerd(s, d) if {
	every k, v in s.spec.selector {
		d.spec.template.metadata.labels[k] == v
	}
}

containerpoort(s, naam) if {
	is_string(naam)
	some d in deployments
	geselecteerd(s, d)
	some c in d.spec.template.spec.containers
	some p in c.ports
	p.name == naam
}

containerpoort(s, nummer) if {
	is_number(nummer)
	some d in deployments
	geselecteerd(s, d)
	some c in d.spec.template.spec.containers
	some p in c.ports
	p.containerPort == nummer
}

# Zonder poort kiest de Gateway er geen; de route levert dan een 500.
deny contains msg if {
	some r in objecten
	r.kind == "HTTPRoute"
	some regel in r.spec.rules
	some ref in regel.backendRefs
	not ref.port
	msg := sprintf("HTTPRoute '%s' wijst naar %s zonder poort", [r.metadata.name, ref.name])
}

deny contains msg if {
	some h in objecten
	h.kind == "HorizontalPodAutoscaler"
	not deployment_naam(h.spec.scaleTargetRef.name)
	msg := sprintf("HPA '%s' schaalt '%s', maar die Deployment staat niet in de release", [h.metadata.name, h.spec.scaleTargetRef.name])
}

deployment_naam(naam) if {
	some d in deployments
	d.metadata.name == naam
}
