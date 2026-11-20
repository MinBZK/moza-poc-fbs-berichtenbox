{{- /*
SPDX-License-Identifier: EUPL-1.2

De onderdelen van de demo en hun bedrading, als één overzicht. templates/onderdelen.yaml maakt
er per onderdeel een Deployment, Service, ServiceAccount, PodDisruptionBudget en NetworkPolicy
van; templates/httproutes.yaml de routes.

Per onderdeel:
  image        volledige referentie
  uid          vaste non-root UID van het image
  poorten      containerpoorten; de Service publiceert dezelfde nummers
  probe        quarkus (de drie /q/health-paden), http (één pad voor alle drie) of tcp
  schrijfbaar  paden waar het image schrijft; een emptyDir, want het root-filesystem is read-only
  mounts       ConfigMaps en Secrets als bestand
  env          EnvVar-lijst
  roept        doelen waarvoor de pod het clientlabel krijgt
  inkomend     per regel: van pods met het clientlabel van `label`, of van de Gateway; met poorten
  uitgaand     TCP-poorten naar elke bestemming (de NetworkPolicy ziet de poort van de pod)

Alles draait met één replica: console, simulator en Toxiproxy houden toestand in het geheugen
(tempo, gedrag, proxies), en twee replica's zouden die per verzoek anders tonen.
*/}}
{{- define "fbs-demo.componenten" -}}
{{- $v := .Values -}}
{{- $r := .Release.Name -}}
{{- $s := $v.stelsel -}}
{{- $personas := include "fbs-demo.naam" (dict "context" . "onderdeel" "personas") -}}
{{- $console := include "fbs-demo.naam" (dict "context" . "onderdeel" "console") -}}
{{- $simulator := include "fbs-demo.naam" (dict "context" . "onderdeel" "simulator") -}}
{{- $proeftuin := include "fbs-demo.naam" (dict "context" . "onderdeel" "proeftuin") -}}
{{- $profiel := include "fbs-demo.naam" (dict "context" . "onderdeel" "profiel") -}}
{{- $notificatie := include "fbs-demo.naam" (dict "context" . "onderdeel" "notificatie") -}}
{{- $toxiproxy := include "fbs-demo.naam" (dict "context" . "onderdeel" "toxiproxy") -}}
{{- $redis := include "fbs-demo.naam" (dict "context" . "onderdeel" "redis") -}}
{{- $demoHost := include "fbs-demo.host" (dict "context" . "onderdeel" "demo") -}}
{{- $uitvraagFqdn := include "fbs-demo.fqdn" (dict "context" . "naam" $s.uitvraag) -}}
{{- $personasFqdn := include "fbs-demo.fqdn" (dict "context" . "naam" $personas) -}}
{{- $dbCa := "/etc/fbs/db-ca/ca.crt" -}}
{{- $jdbc := printf "jdbc:postgresql://$(DB_HOST):$(DB_PORT)/$(DB_NAME)?sslmode=verify-full&sslrootcert=%s" $dbCa -}}
personas:
  image: {{ include "fbs-demo.eigenImage" (dict "context" . "repository" $v.images.personas) }}
  uid: 185
  poorten: [{naam: http, poort: 8098}]
  probe: quarkus
  schrijfbaar: [/tmp]
  inkomend:
    - {label: {{ $personas }}, poorten: [8098]}

console:
  image: {{ include "fbs-demo.eigenImage" (dict "context" . "repository" $v.images.console) }}
  uid: 185
  poorten: [{naam: http, poort: 8095}]
  probe: quarkus
  schrijfbaar: [/tmp]
  mounts:
    - {naam: db-ca, pad: /etc/fbs/db-ca, secret: {{ $v.database.caSecret }}, key: ca.crt, bestand: ca.crt}
  env:
    {{- include "fbs-demo.dbEnv" . | nindent 4 }}
    # Wat de browser ziet: de demo-proxy zet paneel, berichtenbox en uitvraag achter één origin.
    - {name: UITVRAAG_BASIS, value: "https://{{ $demoHost }}/api/v1"}
    - {name: BERICHTENBOX_URL, value: "https://{{ $demoHost }}/moza/berichtenbox/"}
    - {name: UITVRAAG_URL, value: "http://{{ $s.uitvraag }}:{{ $s.servicePoort }}"}
    - {name: MAGAZIJN_A_URL, value: "http://{{ $s.magazijnA }}:{{ $s.servicePoort }}"}
    - {name: MAGAZIJN_B_URL, value: "http://{{ $s.magazijnB }}:{{ $s.servicePoort }}"}
    - {name: MAGAZIJN_A_DB_URL, value: "{{ $jdbc }}"}
    - {name: MAGAZIJN_B_DB_URL, value: "{{ $jdbc }}"}
    - {name: MAGAZIJN_A_DB_USER, value: "$(DB_USERNAME)"}
    - {name: MAGAZIJN_B_DB_USER, value: "$(DB_USERNAME)"}
    - {name: MAGAZIJN_A_DB_PASSWORD, value: "$(DB_PASSWORD)"}
    - {name: MAGAZIJN_B_DB_PASSWORD, value: "$(DB_PASSWORD)"}
    # Gelijk aan DB_SCHEMA van de magazijnen; wijkt het af, dan leegt het paneel een leeg schema.
    - {name: MAGAZIJN_A_DB_SCHEMA, value: {{ $v.database.schemas.magazijnA | quote }}}
    - {name: MAGAZIJN_B_DB_SCHEMA, value: {{ $v.database.schemas.magazijnB | quote }}}
    - {name: MAGAZIJN_SIMULATOR_URL, value: "http://{{ $simulator }}:8092"}
    - name: MAGAZIJN_SIMULATOR_BEHEER_TOKEN
      valueFrom: {secretKeyRef: {name: {{ $v.geheimen.demo }}, key: BEHEER_TOKEN}}
    - {name: BEREIKBAARHEID_PERSONADIENST_URL, value: "http://{{ $personas }}:8098"}
    # Rechtstreeks, niet via Toxiproxy: de Redis-knop legt de sessiecache voor de uitvraag
    # stil, en het paneel moet hem daarna nog kunnen wissen.
    - {name: REDIS_HOSTS, value: "redis://{{ $redis }}:6379"}
    - name: REDIS_PASSWORD
      valueFrom: {secretKeyRef: {name: {{ $v.geheimen.demo }}, key: REDIS_PASSWORD}}
    - {name: TOXIPROXY_ADMIN_URL, value: "http://{{ $toxiproxy }}:8474"}
    - {name: TOXIPROXY_PROFIEL_UPSTREAM, value: "{{ $profiel }}:8080"}
    - {name: TOXIPROXY_NOTIFICATIE_UPSTREAM, value: "{{ $notificatie }}:8080"}
    - {name: TOXIPROXY_AANMELD_UPSTREAM, value: "{{ $s.uitvraag }}:{{ $s.servicePoort }}"}
    - {name: TOXIPROXY_REDIS_UPSTREAM, value: "{{ $redis }}:6379"}
    # Leeg verbergt de knoppen: de uitvraag spreekt de magazijnen aan via hun eigen route, niet
    # via Toxiproxy. Zo ook op ZAD.
    - {name: TOXIPROXY_MAGAZIJN_A_URL, value: ""}
    - {name: TOXIPROXY_MAGAZIJN_B_URL, value: ""}
  roept: [{{ $s.magazijnA }}, {{ $s.magazijnB }}, {{ $s.uitvraag }}, {{ $simulator }}, {{ $personas }}, {{ $toxiproxy }}-beheer, {{ $redis }}]
  inkomend:
    - {label: {{ $console }}, poorten: [8095]}
  uitgaand: [{{ $s.magazijnPoort }}, {{ $s.uitvraagPoort }}, 8092, 8098, 8474, 6379, 5432]

simulator:
  image: {{ include "fbs-demo.eigenImage" (dict "context" . "repository" $v.images.simulator) }}
  uid: 185
  poorten: [{naam: http, poort: 8092}]
  probe: quarkus
  schrijfbaar: [/tmp]
  mounts:
    - {naam: db-ca, pad: /etc/fbs/db-ca, secret: {{ $v.database.caSecret }}, key: ca.crt, bestand: ca.crt}
    # Quarkus leest config/application.properties in de werkmap van het image (/home/jboss).
    - {naam: set, pad: /home/jboss/config, configMap: {{ $simulator }}-set, key: application.properties, bestand: application.properties}
  env:
    {{- include "fbs-demo.dbEnv" . | nindent 4 }}
    - {name: DB_JDBC_URL, value: "{{ $jdbc }}"}
    - {name: DB_SCHEMA, value: {{ $v.database.schemas.simulator | quote }}}
    # De simulator deelt het Cluster met beide magazijnen en het paneel; zijn standaard van vijftig
    # verbindingen zou met de pools van de magazijnen boven max_connections uitkomen.
    - {name: DB_POOL_MAX, value: "20"}
    - name: BEHEER_TOKEN
      valueFrom: {secretKeyRef: {name: {{ $v.geheimen.demo }}, key: BEHEER_TOKEN}}
  inkomend:
    - {label: {{ $simulator }}, poorten: [8092]}
    - {gateway: true, poorten: [8092]}
  uitgaand: [5432]

proeftuin:
  image: {{ $v.images.proeftuin }}
  uid: 101
  poorten: [{naam: http, poort: 8080}]
  probe: {http: /api/livecheck}
  # Het entrypoint van nginx schrijft de config bij het starten in conf.d.
  schrijfbaar: [/tmp, /etc/nginx/conf.d]
  env:
    # De standaarden in het image wijzen naar de testomgeving op ZAD. De demo-proxy vangt
    # /api/v1 en /api/demo al af; deze adressen gelden alleen als er toch iets hier aankomt.
    - {name: BACKEND_KETEN, value: "http://{{ $uitvraagFqdn }}:{{ $s.servicePoort }}"}
    - {name: BACKEND_KETEN_HOST, value: {{ $uitvraagFqdn | quote }}}
    - {name: BACKEND_PERSONAS, value: "http://{{ $personasFqdn }}:8098"}
    - {name: BACKEND_PERSONAS_HOST, value: {{ $personasFqdn | quote }}}
  roept: [{{ $s.uitvraag }}, {{ $personas }}]
  inkomend:
    - {label: {{ $proeftuin }}, poorten: [8080]}
  # 443: /api/profielservice/ gaat naar de profielservice van de proeftuin zelf, buiten het cluster.
  uitgaand: [{{ $s.uitvraagPoort }}, 8098, 443]

demo-proxy:
  image: {{ $v.images.nginx }}
  uid: 101
  poorten: [{naam: http, poort: 8080}]
  probe: {http: /_gezond}
  schrijfbaar: [/tmp, /etc/nginx/conf.d]
  mounts:
    - {naam: config, pad: /etc/nginx/templates, configMap: {{ $r }}-demo-proxy, key: default.conf.template, bestand: default.conf.template}
    - {naam: bediening, pad: /etc/nginx/bediening, secret: {{ $v.geheimen.bediening }}, key: htpasswd, bestand: htpasswd}
  env:
    # Vult ${NGINX_LOCAL_RESOLVERS} uit resolv.conf; alleen die naam, zodat envsubst de variabelen
    # van nginx zelf laat staan.
    - {name: NGINX_ENTRYPOINT_LOCAL_RESOLVERS, value: "1"}
    - {name: NGINX_ENVSUBST_FILTER, value: "^NGINX_LOCAL_RESOLVERS$"}
  roept: [{{ $proeftuin }}, {{ $console }}, {{ $personas }}, {{ $s.uitvraag }}]
  inkomend:
    - {gateway: true, poorten: [8080]}
  uitgaand: [8080, 8095, 8098, {{ $s.uitvraagPoort }}]

profiel:
  image: {{ include "fbs-demo.eigenImage" (dict "context" . "repository" $v.images.profiel) }}
  uid: 1000
  poorten: [{naam: http, poort: 8080}]
  # Een stub-mapping, niet de admin-API: die staat achter een wachtwoord dat bij elke start wisselt.
  probe: {http: /gezondheid}
  schrijfbaar: [/tmp]
  inkomend:
    - {label: {{ $profiel }}, poorten: [8080]}

notificatie:
  image: {{ include "fbs-demo.eigenImage" (dict "context" . "repository" $v.images.notificatie) }}
  uid: 1000
  poorten: [{naam: http, poort: 8080}]
  probe: {http: /gezondheid}
  schrijfbaar: [/tmp]
  inkomend:
    - {label: {{ $notificatie }}, poorten: [8080]}

toxiproxy:
  image: {{ $v.images.toxiproxy }}
  uid: 65532
  poorten:
    - {naam: beheer, poort: 8474}
    - {naam: profiel, poort: 18089}
    - {naam: notificatie, poort: 18084}
    - {naam: aanmeld, poort: 18086}
    - {naam: redis, poort: 16379}
  # Op de admin-poort: de proxies bestaan pas als de console ze aanmaakt, en een probe op een
  # proxy-poort herstart de pod zodra een storing hem uitzet. Dan zijn alle proxies weg.
  probe: {http: /version, poort: beheer}
  inkomend:
    # De admin-API heeft geen authenticatie; alleen het paneel komt erbij.
    - {label: {{ $toxiproxy }}-beheer, poorten: [8474]}
    - {label: {{ $toxiproxy }}, poorten: [16379]}
    - {gateway: true, poorten: [18089, 18084, 18086]}
  roept: [{{ $profiel }}, {{ $notificatie }}, {{ $s.uitvraag }}, {{ $redis }}]
  uitgaand: [8080, {{ $s.uitvraagPoort }}, 6379]

redis:
  image: {{ $v.images.redis }}
  uid: 999
  poorten: [{naam: redis, poort: 6379}]
  probe: {tcp: redis}
  schrijfbaar: [/data, /tmp]
  env:
    - name: REDIS_PASSWORD
      valueFrom: {secretKeyRef: {name: {{ $v.geheimen.demo }}, key: REDIS_PASSWORD}}
    # Zonder persistence: de sessiecache is vluchtig, en zo schrijft Redis niets buiten /data.
    - {name: REDIS_ARGS, value: "--requirepass $(REDIS_PASSWORD) --save '' --appendonly no"}
  inkomend:
    - {label: {{ $redis }}, poorten: [6379]}
{{- end -}}

{{- /* De velden van het Secret van CloudNativePG; de pod stelt er zelf de JDBC-URL uit samen.
     Het veld uri is geen JDBC, en jdbc-uri zet het wachtwoord in de URL. */}}
{{- define "fbs-demo.dbEnv" -}}
{{- $secret := .Values.database.secret -}}
- name: DB_HOST
  valueFrom: {secretKeyRef: {name: {{ $secret }}, key: host}}
- name: DB_PORT
  valueFrom: {secretKeyRef: {name: {{ $secret }}, key: port}}
- name: DB_NAME
  valueFrom: {secretKeyRef: {name: {{ $secret }}, key: dbname}}
- name: DB_USERNAME
  valueFrom: {secretKeyRef: {name: {{ $secret }}, key: username}}
- name: DB_PASSWORD
  valueFrom: {secretKeyRef: {name: {{ $secret }}, key: password}}
{{- end -}}
