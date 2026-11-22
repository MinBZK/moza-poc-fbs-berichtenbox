{{- /*
SPDX-License-Identifier: EUPL-1.2
*/}}

{{- /* De volledige naam van een onderdeel: <release>-<onderdeel>. */}}
{{- define "fbs-demo.naam" -}}
{{- printf "%s-%s" .context.Release.Name .onderdeel -}}
{{- end -}}

{{- /* De hostnaam van een onderdeel met een route. */}}
{{- define "fbs-demo.host" -}}
{{- printf "%s.%s" .onderdeel .context.Values.basisdomein -}}
{{- end -}}

{{- /* Het adres van een onderdeel zoals de browser het ziet, met de poort alleen als die niet 443 is. */}}
{{- define "fbs-demo.origin" -}}
{{- $poort := int .context.Values.httpsPoort -}}
https://{{ include "fbs-demo.host" . }}{{ if ne $poort 443 }}:{{ $poort }}{{ end }}
{{- end -}}

{{- /* Een Service in deze namespace bij zijn volledige naam: nginx zoekt namen op zonder de
     zoekdomeinen uit resolv.conf. */}}
{{- define "fbs-demo.fqdn" -}}
{{- printf "%s.%s.svc.%s" .naam .context.Release.Namespace .context.Values.clusterDomain -}}
{{- end -}}

{{- /* Het clientlabel waarmee een pod een onderdeel of stelseldienst mag aanroepen; dezelfde
     sleutel als in fbs-dienst. */}}
{{- define "fbs-demo.clientlabel" -}}
client.fbs.minbzk.nl/{{ . }}
{{- end -}}

{{- define "fbs-demo.labels" -}}
app.kubernetes.io/name: {{ .onderdeel }}
app.kubernetes.io/instance: {{ .context.Release.Name }}
app.kubernetes.io/version: {{ .context.Chart.AppVersion | quote }}
app.kubernetes.io/component: {{ .onderdeel }}
app.kubernetes.io/managed-by: {{ .context.Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .context.Chart.Name .context.Chart.Version }}
{{- range $k, $v := .context.Values.commonLabels }}
{{ $k }}: {{ $v | quote }}
{{- end }}
{{- end -}}

{{- define "fbs-demo.selector" -}}
app.kubernetes.io/name: {{ .onderdeel }}
app.kubernetes.io/instance: {{ .context.Release.Name }}
{{- end -}}

{{- /* Een image van deze repository, op de tag van de release. */}}
{{- define "fbs-demo.eigenImage" -}}
{{- printf "%s:%s" .repository .context.Values.images.tag -}}
{{- end -}}
