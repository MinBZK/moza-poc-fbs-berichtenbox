{{- /*
SPDX-License-Identifier: EUPL-1.2
*/}}

{{- define "fbs-dienst.component" -}}api{{- end -}}

{{- define "fbs-dienst.image" -}}
{{- include "common.images.image" (dict "imageRoot" .Values.dienst.image "global" .Values.global) -}}
{{- end -}}

{{- define "fbs-dienst.imagePullSecrets" -}}
{{- include "common.images.renderPullSecrets" (dict "images" (list .Values.dienst.image) "context" $) -}}
{{- end -}}

{{- define "fbs-dienst.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "common.names.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- required "serviceAccount.name is verplicht als serviceAccount.create=false: het default-account is niet toegestaan" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{- define "fbs-dienst.podLabels" -}}
{{- include "common.tplvalues.merge" (dict "values" (list .Values.dienst.podLabels .Values.commonLabels) "context" .) -}}
{{- end -}}

{{/*
Faalt de render bij values die in het cluster niet kunnen werken. Een fout bij `helm template`
komt vóór de uitrol boven; een fout in het cluster pas als de pod niet start.
*/}}
{{- define "fbs-dienst.validateValues" -}}
{{- $fouten := list -}}
{{- $d := .Values.dienst -}}
{{- if not $d.image.repository -}}
{{- $fouten = append $fouten "dienst.image.repository is leeg: geef een values-bestand uit diensten/ mee" -}}
{{- end -}}
{{- if and (not $d.image.tag) (not $d.image.digest) -}}
{{- $fouten = append $fouten "dienst.image.tag of dienst.image.digest is verplicht (bijvoorbeeld main-<sha7>); er is geen latest" -}}
{{- else if and $d.image.tag (not $d.image.digest) (not (regexMatch "^(main|pr-[0-9]+)-[0-9a-f]{7}(-[rk][0-9]+)*$" (toString $d.image.tag))) -}}
{{- $fouten = append $fouten (printf "dienst.image.tag %q is geen main-<sha7> of pr-<n>-<sha7> (eventueel met -r<n>/-k<n>): een meebewegende tag laat een herinstallatie stil een ander image draaien; gebruik anders dienst.image.digest" (toString $d.image.tag)) -}}
{{- end -}}
{{- if eq (toString $d.tlsTermination) "app" -}}
{{- $fouten = append $fouten "dienst.tlsTermination=app levert de chart niet: daarvoor zijn een https-poort, probes op HTTPS en TLS vanaf de Gateway nodig" -}}
{{- else if ne (toString $d.tlsTermination) "mesh" -}}
{{- $fouten = append $fouten (printf "dienst.tlsTermination %q is ongeldig: zet mesh, en lees in values.yaml wat dat vraagt van het pod-netwerk" (toString $d.tlsTermination)) -}}
{{- end -}}
{{- /* Namen zoals Quarkus ze leest: SmallRye vertaalt fbs.redis.unsafe-allow-plaintext en
     FBS_REDIS_UNSAFE_ALLOW_PLAINTEXT naar dezelfde property. */}}
{{- $namen := keys $d.env -}}
{{- range $d.extraEnvVars -}}
{{- $namen = append $namen (toString .name) -}}
{{- end -}}
{{- $onveiligToegestaan := eq (toString $d.allowUnsafeFlags) "true" -}}
{{- range $namen -}}
{{- $genormaliseerd := . | upper | replace "." "_" | replace "-" "_" -}}
{{- if and (contains "UNSAFE" $genormaliseerd) (not $onveiligToegestaan) -}}
{{- $fouten = append $fouten (printf "%s zet een TLS-eis uit; dat kan alleen met dienst.allowUnsafeFlags=true" .) -}}
{{- end -}}
{{- if eq $genormaliseerd "HTTP_TLS_TERMINATION" -}}
{{- $fouten = append $fouten (printf "%s staat in dienst.env of extraEnvVars: zet dienst.tlsTermination" .) -}}
{{- end -}}
{{- /* Een ander profiel (dev, test) schakelt elke TLS-eis uit; JVM-opties kunnen het profiel zetten. */}}
{{- if has $genormaliseerd (list "QUARKUS_PROFILE" "JAVA_TOOL_OPTIONS" "JDK_JAVA_OPTIONS" "_JAVA_OPTIONS" "JAVA_OPTS" "JAVA_OPTS_APPEND" "JAVA_OPTIONS") -}}
{{- $fouten = append $fouten (printf "%s kan het Quarkus-profiel wijzigen en daarmee de TLS-eisen uitzetten; de chart laat dat niet toe" .) -}}
{{- end -}}
{{- end -}}
{{- if and $d.keystore.enabled (or (not $d.keystore.secretName) (not $d.keystore.key) (not $d.keystore.mountPath)) -}}
{{- $fouten = append $fouten "dienst.keystore vraagt secretName, key en mountPath" -}}
{{- end -}}
{{- $gw := .Values.networkPolicy.gatewayEgress -}}
{{- if and $gw.enabled (or (not $gw.namespaceMatchLabels) (not $gw.podMatchLabels)) -}}
{{- $fouten = append $fouten "networkPolicy.gatewayEgress vraagt namespaceMatchLabels en podMatchLabels: anders telt elke pod mee" -}}
{{- end -}}
{{- if and $d.autoscaling.hpa.enabled (gt (int $d.autoscaling.hpa.minReplicas) (int $d.autoscaling.hpa.maxReplicas)) -}}
{{- $fouten = append $fouten (printf "dienst.autoscaling.hpa.minReplicas (%v) is groter dan maxReplicas (%v)" $d.autoscaling.hpa.minReplicas $d.autoscaling.hpa.maxReplicas) -}}
{{- end -}}
{{- if and $d.autoscaling.hpa.enabled (not $d.autoscaling.hpa.targetCPU) (not $d.autoscaling.hpa.targetMemory) -}}
{{- $fouten = append $fouten "dienst.autoscaling.hpa vraagt targetCPU of targetMemory" -}}
{{- end -}}
{{- if not (has .Values.cluster.routingMode (list "gateway-api" "ingress")) -}}
{{- $fouten = append $fouten (printf "cluster.routingMode %q is ongeldig: kies gateway-api of ingress" .Values.cluster.routingMode) -}}
{{- end -}}
{{- if .Values.ingress.enabled -}}
{{- if not .Values.ingress.hostname -}}
{{- $fouten = append $fouten "ingress.hostname is verplicht als ingress.enabled=true" -}}
{{- end -}}
{{- if and (eq .Values.cluster.routingMode "gateway-api") (not .Values.cluster.gateway.name) -}}
{{- $fouten = append $fouten "cluster.gateway.name is verplicht bij cluster.routingMode=gateway-api" -}}
{{- end -}}
{{- if and (eq .Values.cluster.routingMode "ingress") (not .Values.ingress.tls) -}}
{{- $fouten = append $fouten "ingress.tls is verplicht bij cluster.routingMode=ingress: zonder TLS gaat het BSN onversleuteld over de publieke verbinding" -}}
{{- end -}}
{{- if not .Values.ingress.paths -}}
{{- $fouten = append $fouten "ingress.paths is leeg: de route zou niets doorlaten" -}}
{{- end -}}
{{- range .Values.ingress.paths -}}
{{- if not (has (toString .type) (list "PathPrefix" "Exact")) -}}
{{- $fouten = append $fouten (printf "ingress.paths: type %q is niet toegestaan, alleen PathPrefix of Exact" (toString .type)) -}}
{{- end -}}
{{- end -}}
{{- if ne (toString .Values.ingress.allowUnauthenticated) "true" -}}
{{- $fouten = append $fouten "ingress.enabled vraagt ingress.allowUnauthenticated=true: de API authenticeert zijn aanroepers niet, dus wie de route bereikt, bereikt de API" -}}
{{- end -}}
{{- end -}}
{{- /* common.names.fullname kapt stil af op 63 tekens; twee releases die pas daarna verschillen,
     zouden dan elkaars objecten overschrijven. */}}
{{- $naam := default .Chart.Name .Values.nameOverride -}}
{{- $volledig := ternary .Release.Name (printf "%s-%s" .Release.Name $naam) (contains $naam .Release.Name) -}}
{{- if and (not .Values.fullnameOverride) (gt (len $volledig) 63) -}}
{{- $fouten = append $fouten (printf "de naam %q is langer dan 63 tekens: kies een kortere releasenaam of zet fullnameOverride" $volledig) -}}
{{- end -}}
{{- if $d.singleReplica -}}
{{- if ne (int $d.replicaCount) 1 -}}
{{- $fouten = append $fouten (printf "dienst.replicaCount is %v, maar deze dienst draait met precies één replica" $d.replicaCount) -}}
{{- end -}}
{{- if $d.autoscaling.hpa.enabled -}}
{{- $fouten = append $fouten "dienst.autoscaling.hpa.enabled kan niet: deze dienst draait met precies één replica" -}}
{{- end -}}
{{- end -}}
{{- if $fouten -}}
{{- fail (printf "\nVALUES ONGELDIG:\n- %s" (join "\n- " $fouten)) -}}
{{- end -}}
{{- end -}}
