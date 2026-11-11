# SPDX-License-Identifier: EUPL-1.2
#
# Eigen regels voor de chart fbs-dienst, naast de regels die van mijn-bureau-infra zijn
# overgenomen (containers, image, labels, namespace). Ze bewaken de standaarden die bij een
# vergeten value stil zouden verdwijnen.
package main

import rego.v1

containers contains c if {
	input.kind == "Deployment"
	some c in input.spec.template.spec.containers
}

deny contains msg if {
	some c in containers
	some probe in ["livenessProbe", "readinessProbe", "startupProbe"]
	not c[probe]
	msg := sprintf("Container '%s' mist een %s", [c.name, probe])
}

deny contains msg if {
	some c in containers
	not c.securityContext.readOnlyRootFilesystem
	msg := sprintf("Container '%s' heeft geen read-only root-filesystem", [c.name])
}

deny contains msg if {
	some c in containers
	not "ALL" in object.get(c, ["securityContext", "capabilities", "drop"], [])
	msg := sprintf("Container '%s' laat niet alle capabilities vallen", [c.name])
}

seccomp_runtime_default(c) if c.securityContext.seccompProfile.type == "RuntimeDefault"

seccomp_runtime_default(_) if input.spec.template.spec.securityContext.seccompProfile.type == "RuntimeDefault"

deny contains msg if {
	some c in containers
	not seccomp_runtime_default(c)
	msg := sprintf("Container '%s' draait zonder seccomp-profiel RuntimeDefault", [c.name])
}

deny contains msg if {
	some c in containers
	some soort in ["requests", "limits"]
	not c.resources[soort]["ephemeral-storage"]
	msg := sprintf("Container '%s' mist ephemeral-storage in resources.%s", [c.name, soort])
}

deny contains msg if {
	input.kind == "Deployment"
	# Afwezig betekent in Kubernetes: wél mounten.
	object.get(input.spec.template.spec, "automountServiceAccountToken", true) != false
	msg := "De pod krijgt een service-account-token; de diensten praten niet met de Kubernetes-API"
}

# Het stelselregister mag nooit met twee instanties tegelijk draaien, ook niet tijdens een uitrol.
deny contains msg if {
	input.kind == "Deployment"
	input.metadata.labels["app.kubernetes.io/name"] == "stelselregister"
	input.spec.replicas != 1
	msg := sprintf("Het stelselregister heeft %v replica's; het draait met precies één", [input.spec.replicas])
}

deny contains msg if {
	input.kind == "Deployment"
	input.metadata.labels["app.kubernetes.io/name"] == "stelselregister"
	object.get(input.spec, ["strategy", "type"], "RollingUpdate") != "Recreate"
	msg := "Het stelselregister rolt niet uit met Recreate; tijdens de uitrol draaien er dan twee"
}

deny contains msg if {
	input.kind == "HorizontalPodAutoscaler"
	input.metadata.labels["app.kubernetes.io/name"] == "stelselregister"
	msg := "Het stelselregister mag geen HPA hebben"
}

# Een regel zonder `from` laat inkomend verkeer van overal toe.
deny contains msg if {
	input.kind == "NetworkPolicy"
	some regel in input.spec.ingress
	not regel.from
	msg := sprintf("NetworkPolicy '%s' laat inkomend verkeer van overal toe", [input.metadata.name])
}

# Een regel zonder `to` en zonder `ports` laat uitgaand verkeer naar overal toe.
deny contains msg if {
	input.kind == "NetworkPolicy"
	some regel in input.spec.egress
	not regel.to
	not regel.ports
	msg := sprintf("NetworkPolicy '%s' laat uitgaand verkeer naar overal toe", [input.metadata.name])
}

deny contains msg if {
	input.kind == "NetworkPolicy"
	not "Egress" in input.spec.policyTypes
	msg := sprintf("NetworkPolicy '%s' beperkt uitgaand verkeer niet", [input.metadata.name])
}

deny contains msg if {
	input.kind == "HTTPRoute"
	count(object.get(input.spec, "hostnames", [])) == 0
	msg := sprintf("HTTPRoute '%s' heeft geen hostnaam en vangt dan elk verkeer op de Gateway", [input.metadata.name])
}
