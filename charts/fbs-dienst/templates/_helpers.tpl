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
{{- end -}}
{{- if $d.preciesEenReplica -}}
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
