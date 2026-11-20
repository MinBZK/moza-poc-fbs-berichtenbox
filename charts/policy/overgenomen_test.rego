# Per overgenomen bestand één regel die afgaat. Zo valt een hernoemd package of een hernoemde
# deny-regel op: conftest test blijft dan gewoon groen, met een paar regels minder.
package main

import rego.v1

kaal_deployment := {
	"kind": "Deployment",
	"metadata": {"name": "d", "namespace": "fbs"},
	"spec": {"template": {"spec": {
		"serviceAccountName": "d",
		"containers": [{"name": "c", "image": "ghcr.io/minbzk/fbs-x:main-0123abc", "imagePullPolicy": "Always"}],
	}}},
}

# containers.rego
test_overgenomen_latest_tag_faalt if {
	d := json.patch(kaal_deployment, [{"op": "replace", "path": "/spec/template/spec/containers/0/image", "value": "x:latest"}])
	"Container 'c' uses 'latest' tag" in deny with input as d
}

# image.rego
test_overgenomen_pull_policy_faalt if {
	d := json.patch(kaal_deployment, [{"op": "replace", "path": "/spec/template/spec/containers/0/imagePullPolicy", "value": "IfNotPresent"}])
	"Container 'c' in Deployment/d must have imagePullPolicy set to 'Always', got 'IfNotPresent'" in deny with input as d
}

# labels.rego
test_overgenomen_labels_faalt if {
	some m in deny with input as kaal_deployment
	startswith(m, "Resource Deployment/d is missing minimum required labels")
}

# namespace.rego
test_overgenomen_namespace_faalt if {
	d := json.patch(kaal_deployment, [{"op": "remove", "path": "/metadata/namespace"}])
	"Resource \"d\" of kind \"Deployment\" does not have a namespace defined." in deny with input as d
}
