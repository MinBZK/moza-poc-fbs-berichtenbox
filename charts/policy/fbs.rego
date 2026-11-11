# SPDX-License-Identifier: EUPL-1.2
#
# Eigen regels voor de chart fbs-dienst, naast de regels die van mijn-bureau-infra zijn
# overgenomen (containers, image, labels, namespace). Ze bewaken de standaarden die bij een
# vergeten value stil zouden verdwijnen.
package main

import rego.v1

pod_spec := input.spec.template.spec if input.kind == "Deployment"

# Containers en initContainers: een initContainer draait met dezelfde rechten op dezelfde node.
containers contains c if {
	some c in pod_spec.containers
}

containers contains c if {
	some c in object.get(pod_spec, "initContainers", [])
}

probe_plichtig contains c if {
	some c in pod_spec.containers
}

deny contains msg if {
	some c in probe_plichtig
	some probe in ["livenessProbe", "readinessProbe", "startupProbe"]
	not c[probe]
	msg := sprintf("Container '%s' mist een %s", [c.name, probe])
}

deny contains msg if {
	some c in containers
	# Afwezig of null betekent in Kubernetes: schrijfbaar.
	object.get(c, ["securityContext", "readOnlyRootFilesystem"], false) != true
	msg := sprintf("Container '%s' heeft geen read-only root-filesystem", [c.name])
}

deny contains msg if {
	some c in containers
	not "ALL" in object.get(c, ["securityContext", "capabilities", "drop"], [])
	msg := sprintf("Container '%s' laat niet alle capabilities vallen", [c.name])
}

deny contains msg if {
	some c in containers
	gevuld(object.get(c, ["securityContext", "capabilities"], {}), "add")
	msg := sprintf("Container '%s' voegt capabilities toe", [c.name])
}

# De waarde op de container gaat in Kubernetes vóór die op de pod.
seccomp_runtime_default(c) if c.securityContext.seccompProfile.type == "RuntimeDefault"

seccomp_runtime_default(c) if {
	not c.securityContext.seccompProfile
	pod_spec.securityContext.seccompProfile.type == "RuntimeDefault"
}

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
	object.get(pod_spec, "automountServiceAccountToken", true) != false
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

# Een lijst die afwezig, null of leeg is, betekent in een NetworkPolicy: geen beperking.
gevuld(obj, sleutel) if {
	is_array(obj[sleutel])
	count(obj[sleutel]) > 0
}

# Een peer met alleen lege selectors betekent: alle pods, of alle namespaces.
open_peer(peer) if {
	object.keys(peer) & {"podSelector", "namespaceSelector", "ipBlock"} != set()
	not peer.ipBlock
	every sleutel in ["podSelector", "namespaceSelector"] {
		count(object.get(object.get(peer, sleutel, {}), "matchLabels", {})) == 0
		count(object.get(object.get(peer, sleutel, {}), "matchExpressions", [])) == 0
	}
}

deny contains msg if {
	input.kind == "NetworkPolicy"
	some regel in object.get(input.spec, "ingress", [])
	not gevuld(regel, "from")
	msg := sprintf("NetworkPolicy '%s' laat inkomend verkeer van overal toe", [input.metadata.name])
}

deny contains msg if {
	input.kind == "NetworkPolicy"
	some regel in object.get(input.spec, "ingress", [])
	some peer in regel.from
	open_peer(peer)
	msg := sprintf("NetworkPolicy '%s' heeft een bron met lege selectors; die laat alles toe", [input.metadata.name])
}

deny contains msg if {
	input.kind == "NetworkPolicy"
	some regel in object.get(input.spec, "egress", [])
	not gevuld(regel, "to")
	not gevuld(regel, "ports")
	msg := sprintf("NetworkPolicy '%s' laat uitgaand verkeer naar overal toe", [input.metadata.name])
}

deny contains msg if {
	input.kind == "NetworkPolicy"
	not "Egress" in input.spec.policyTypes
	msg := sprintf("NetworkPolicy '%s' beperkt uitgaand verkeer niet", [input.metadata.name])
}

deny contains msg if {
	input.kind == "HTTPRoute"
	not gevuld(input.spec, "hostnames")
	msg := sprintf("HTTPRoute '%s' heeft geen hostnaam en vangt dan elk verkeer op de Gateway", [input.metadata.name])
}

# /q bevat health en Swagger UI; een health-antwoord noemt bij een storing interne hostnamen.
route_pad_open(pad) if {
	pad.type == "PathPrefix"
	pad.value in {"/", "/q", "/q/"}
}

route_pad_open(pad) if startswith(pad.value, "/q/")

deny contains msg if {
	input.kind == "HTTPRoute"
	some regel in input.spec.rules
	some match in regel.matches
	route_pad_open(match.path)
	msg := sprintf("HTTPRoute '%s' zet %s open; /q hoort binnen het cluster", [input.metadata.name, match.path.value])
}

# Een regel zonder matches vangt in Gateway API alles, als PathPrefix /.
deny contains msg if {
	input.kind == "HTTPRoute"
	some regel in input.spec.rules
	not gevuld(regel, "matches")
	msg := sprintf("HTTPRoute '%s' heeft een regel zonder matches; die zet alles open", [input.metadata.name])
}

deny contains msg if {
	input.kind == "Ingress"
	some regel in input.spec.rules
	some pad in regel.http.paths
	route_pad_open({"type": object.get({"Prefix": "PathPrefix"}, pad.pathType, pad.pathType), "value": pad.path})
	msg := sprintf("Ingress '%s' zet %s open; /q hoort binnen het cluster", [input.metadata.name, pad.path])
}
