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
     FBS_REDIS_UNSAFE_ALLOW_PLAINTEXT naar dezelfde property. extraEnvVars na de render: een naam
     mag een template zijn, en pas het resultaat komt in de container. */}}
{{- $namen := list -}}
{{- if and $d.env (not (kindIs "map" $d.env)) -}}
{{- $fouten = append $fouten "dienst.env is geen map van naam naar waarde" -}}
{{- else -}}
{{- $namen = keys (default dict $d.env) -}}
{{- end -}}
{{- if and $d.extraEnvVars (not (kindIs "slice" $d.extraEnvVars)) -}}
{{- $fouten = append $fouten "dienst.extraEnvVars is geen lijst van {name, value}" -}}
{{- else -}}
{{- range include "common.tplvalues.render" (dict "value" (default list $d.extraEnvVars) "context" $) | fromYamlArray -}}
{{- $namen = append $namen (toString (get . "name")) -}}
{{- end -}}
{{- end -}}
{{- $onveiligToegestaan := eq (toString $d.allowUnsafeFlags) "true" -}}
{{- /* Namen waarmee een ander profiel (dev, test) of een ander config-bestand te zetten is: in zo'n
     profiel vervalt elke TLS-eis. Het image start via run-java.sh, dat GC_*, JAVA_* en JBOSS_* op
     de java-opdrachtregel zet of er scripts uit laadt; daar past ook -Dquarkus.profile in. */}}
{{- $profielPatroon := "^(QUARKUS_PROFILE|QUARKUS_CONFIG_|SMALLRYE_CONFIG_|GC_|JAVA_|JDK_|_JAVA_|JBOSS_)" -}}
{{- range $namen -}}
{{- if not (regexMatch "^[A-Za-z_][A-Za-z0-9_.-]*$" .) -}}
{{- $fouten = append $fouten (printf "env-naam %q is ongeldig: alleen letters, cijfers, _ . en -, en het begint met een letter of _" .) -}}
{{- end -}}
{{- $genormaliseerd := . | upper | replace "." "_" | replace "-" "_" -}}
{{- /* SmallRye leest _PROD_X als %prod.x; die vorm wint van de %prod.-waarde in de dienst. */}}
{{- $zonderProfiel := regexReplaceAll "^_[A-Z0-9]+_" $genormaliseerd "" -}}
{{- if and (contains "UNSAFE" $genormaliseerd) (not $onveiligToegestaan) -}}
{{- $fouten = append $fouten (printf "%s zet een TLS-eis uit; dat kan alleen met dienst.allowUnsafeFlags=true" .) -}}
{{- end -}}
{{- if or (has $genormaliseerd (list "HTTP_TLS_TERMINATION" "FBS_HTTP_TLS_TERMINATION")) (has $zonderProfiel (list "HTTP_TLS_TERMINATION" "FBS_HTTP_TLS_TERMINATION")) -}}
{{- $fouten = append $fouten (printf "%s staat in dienst.env of extraEnvVars: zet dienst.tlsTermination" .) -}}
{{- end -}}
{{- if or (eq $genormaliseerd "QUARKUS_HTTP_PORT") (eq $zonderProfiel "QUARKUS_HTTP_PORT") -}}
{{- $fouten = append $fouten (printf "%s staat in dienst.env of extraEnvVars: zet dienst.containerPorts.http, anders wijzen probes en Service naar een andere poort" .) -}}
{{- end -}}
{{- if or (regexMatch $profielPatroon $genormaliseerd) (regexMatch $profielPatroon $zonderProfiel) -}}
{{- $fouten = append $fouten (printf "%s kan het Quarkus-profiel of de config wijzigen en daarmee de TLS-eisen uitzetten; de chart laat dat niet toe" .) -}}
{{- end -}}
{{- end -}}
{{- /* Met een eigen command of args is ook het profiel te zetten. */}}
{{- if or $d.command $d.args -}}
{{- $fouten = append $fouten "dienst.command en dienst.args kan de chart niet toestaan: command vervangt het entrypoint en args de argumenten ervan, en zo is de start van de dienst buiten deze controles om te wijzigen" -}}
{{- end -}}
{{- if and $d.keystore.enabled (or (not $d.keystore.secretName) (not $d.keystore.key) (not $d.keystore.mountPath)) -}}
{{- $fouten = append $fouten "dienst.keystore vraagt secretName, key en mountPath" -}}
{{- end -}}
{{- $np := .Values.networkPolicy -}}
{{- $gw := $np.gatewayEgress -}}
{{- if and $gw.enabled (or (not $gw.namespaceMatchLabels) (not $gw.podMatchLabels)) -}}
{{- $fouten = append $fouten "networkPolicy.gatewayEgress vraagt namespaceMatchLabels en podMatchLabels: anders telt elke pod mee" -}}
{{- end -}}
{{- /* Een egress-regel zonder poorten laat in Kubernetes elke poort toe. */}}
{{- if and $np.enabled (not $np.allowExternalEgress) -}}
{{- if not $np.dnsPorts -}}
{{- $fouten = append $fouten "networkPolicy.dnsPorts is leeg: de DNS-regel zou dan elke poort naar overal toelaten" -}}
{{- end -}}
{{- if and $gw.enabled (not $gw.ports) -}}
{{- $fouten = append $fouten "networkPolicy.gatewayEgress.ports is leeg: de regel zou dan elke poort naar de Gateway-proxy's toelaten" -}}
{{- end -}}
{{- end -}}
{{- /* Een lege labelwaarde selecteert geen enkele pod, en dan komt de Gateway of controller niet
     binnen. Bij routingMode=ingress gebeurt dat met de standaard, die naar de Gateway kijkt. */}}
{{- if .Values.ingress.enabled -}}
{{- with $np.ingressNSPodMatchLabels -}}
{{- range $sleutel, $waarde := (include "common.tplvalues.render" (dict "value" . "context" $) | fromYaml) -}}
{{- if not $waarde -}}
{{- $fouten = append $fouten (printf "networkPolicy.ingressNSPodMatchLabels: %s is leeg; zet de labels van de pods die binnen moeten komen, of null" $sleutel) -}}
{{- end -}}
{{- end -}}
{{- end -}}
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
{{- if and (eq .Values.cluster.routingMode "gateway-api") (not .Values.cluster.gateway.sectionName) -}}
{{- $fouten = append $fouten "cluster.gateway.sectionName is verplicht bij cluster.routingMode=gateway-api: zonder hangt de route ook aan een listener zonder TLS, en gaat het BSN onversleuteld over de publieke verbinding" -}}
{{- end -}}
{{- if and (eq .Values.cluster.routingMode "ingress") (not .Values.ingress.tls) -}}
{{- $fouten = append $fouten "ingress.tls is verplicht bij cluster.routingMode=ingress: zonder TLS gaat het BSN onversleuteld over de publieke verbinding" -}}
{{- end -}}
{{- if not .Values.ingress.paths -}}
{{- $fouten = append $fouten "ingress.paths is leeg: de route zou niets doorlaten" -}}
{{- end -}}
{{- range .Values.ingress.paths -}}
{{- $waarde := toString .value -}}
{{- if not (has (toString .type) (list "PathPrefix" "Exact")) -}}
{{- $fouten = append $fouten (printf "ingress.paths: type %q is niet toegestaan, alleen PathPrefix of Exact" (toString .type)) -}}
{{- end -}}
{{- if not (hasPrefix "/" $waarde) -}}
{{- $fouten = append $fouten (printf "ingress.paths: %q begint niet met /" $waarde) -}}
{{- end -}}
{{- /* /q bevat health en Swagger UI; een health-antwoord noemt bij een storing interne hostnamen. */}}
{{- if or (and (eq (toString .type) "PathPrefix") (has $waarde (list "/" "/q" "/q/"))) (eq $waarde "/q") (hasPrefix "/q/" $waarde) -}}
{{- $fouten = append $fouten (printf "ingress.paths: %q zet /q open; dat hoort binnen het cluster" $waarde) -}}
{{- end -}}
{{- end -}}
{{- if and .Values.ingress.streamPaths (eq .Values.cluster.routingMode "ingress") -}}
{{- $fouten = append $fouten "ingress.streamPaths werkt alleen met een HTTPRoute: bij routingMode=ingress breekt de Ingress-controller de streams op zijn eigen timeout af; zet ingress.streamPaths=null en regel de timeout in de controller" -}}
{{- end -}}
{{- if and .Values.ingress.requestTimeout (eq .Values.cluster.routingMode "ingress") -}}
{{- $fouten = append $fouten "ingress.requestTimeout werkt alleen met een HTTPRoute: bij routingMode=ingress geldt de timeout van de Ingress-controller; zet ingress.requestTimeout=null en regel de timeout in de controller" -}}
{{- end -}}
{{- range .Values.ingress.streamPaths -}}
{{- $stream := toString . -}}
{{- $gedekt := false -}}
{{- range $.Values.ingress.paths -}}
{{- if or (eq $stream (toString .value)) (and (eq (toString .type) "PathPrefix") (hasPrefix (printf "%s/" (trimSuffix "/" (toString .value))) $stream)) -}}
{{- $gedekt = true -}}
{{- end -}}
{{- end -}}
{{- /* ingress.paths sluit /q al uit, dus een stream daaronder is nooit gedekt. */}}
{{- if not $gedekt -}}
{{- $fouten = append $fouten (printf "ingress.streamPaths: %q valt niet onder ingress.paths" $stream) -}}
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
