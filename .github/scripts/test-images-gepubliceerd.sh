#!/usr/bin/env bash
# Unittests voor images-gepubliceerd.sh, en de kruiscontrole met deploy.yml. Geen netwerk: een
# docker-stub op het pad kent een vaste set images.
#
# De kruiscontrole is het deel dat een fixture nooit ziet: dat de uitrol alleen images verwacht die
# een build-job controleert, en dat een build-job alleen images controleert die de uitrol verwacht.

set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$HERE/../.." && pwd)
readonly HERE REPO_ROOT
readonly SCRIPT="$HERE/images-gepubliceerd.sh"
readonly DEPLOY_YML="$REPO_ROOT/.github/workflows/deploy.yml"
readonly CLEANUP_YML="$REPO_ROOT/.github/workflows/cleanup-preview.yml"

asserties=0
mislukt=0

ok() {
  asserties=$((asserties + 1))
  echo "OK: $1"
}

fout() {
  mislukt=1
  echo "FOUT: $1"
}

gelijk() {
  local wat=$1 verwacht=$2 gemeten=$3

  if [ "$verwacht" = "$gemeten" ]; then
    ok "$wat"
  else
    fout "$wat — verwacht '$verwacht', gemeten '$gemeten'"
  fi
}

bevat() {
  local wat=$1 naald=$2 hooiberg=$3

  case "$hooiberg" in
    *"$naald"*) ok "$wat" ;;
    *) fout "$wat — '$naald' ontbreekt in: $hooiberg" ;;
  esac
}

bevat_niet() {
  local wat=$1 naald=$2 hooiberg=$3

  case "$hooiberg" in
    *"$naald"*) fout "$wat — '$naald' had niet mogen voorkomen in: $hooiberg" ;;
    *) ok "$wat" ;;
  esac
}

werkmap=$(mktemp -d)
trap 'rm -rf "$werkmap"' EXIT

# De vormen die een register teruggeeft: een los manifest (zoals jib), de index die `docker build`
# met provenance-attestation maakt, en een index voor beide platforms.
readonly LOS='{"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json","config":{"digest":"sha256:c"},"layers":[]}'
readonly INDEX_AMD64='{"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[{"platform":{"os":"linux","architecture":"amd64"}},{"platform":{"os":"unknown","architecture":"unknown"},"annotations":{"vnd.docker.reference.type":"attestation-manifest"}}]}'
readonly INDEX_BEIDE='{"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[{"platform":{"os":"linux","architecture":"amd64"}},{"platform":{"os":"linux","architecture":"arm64","variant":"v8"}}]}'

# De stub kent de refs in `bestaat` en geeft ze als los manifest, tenzij `vorm` er met zet_vorm een
# andere voor heeft. Een ref in `hikt` faalt bij de eerste opvraging en bestaat daarna, zoals een
# register na een 5xx.
maak_stub() {
  printf '%s\n' "$@" >"$werkmap/bestaat"
  : >"$werkmap/hikt"
  rm -rf "$werkmap/aanroepen" "$werkmap/gehikt" "$werkmap/vorm"
  mkdir -p "$werkmap/vorm"

  cat >"$werkmap/docker" <<STUB
#!/usr/bin/env bash
ref=\${!#}
printf '%s\n' "\$*" >>"$werkmap/aanroepen"

if grep -qxF "\$ref" "$werkmap/hikt" && ! grep -qxF "\$ref" "$werkmap/gehikt" 2>/dev/null; then
  echo "\$ref" >>"$werkmap/gehikt"
  echo "ERROR: 503 Service Unavailable" >&2
  exit 1
fi

if grep -qxF "\$ref" "$werkmap/bestaat"; then
  vorm="$werkmap/vorm/\${ref//[\/:]/_}"
  [ -f "\$vorm" ] && cat "\$vorm" || printf '%s\n' '$LOS'
  exit 0
fi

echo "ERROR: \$ref: not found" >&2
exit 1
STUB
  chmod +x "$werkmap/docker"
}

# $1 = ref, $2 = het manifest dat de stub ervoor teruggeeft.
zet_vorm() {
  printf '%s\n' "$2" >"$werkmap/vorm/${1//[\/:]/_}"
}

draai() {
  RC=0
  UITVOER=$(IMAGES_DOCKER="$werkmap/docker" IMAGES_WACHT=0 bash "$SCRIPT" "$@" 2>&1) || RC=$?
  AANROEPEN=$(cat "$werkmap/aanroepen" 2>/dev/null || true)
}

readonly B=ghcr.io/minbzk

# --- alles gepubliceerd: één, en meerdere images -------------------------------------------------
maak_stub "$B/fbs-a:t1"
draai "$B" t1 fbs-a
gelijk "één gepubliceerd image slaagt" 0 "$RC"
bevat "het register wordt naar naam en tag gevraagd" "buildx imagetools inspect --raw $B/fbs-a:t1" "$AANROEPEN"
gelijk "een gepubliceerd image wordt één keer opgevraagd" 1 "$(grep -c . <<<"$AANROEPEN")"

maak_stub "$B/fbs-a:t1" "$B/fbs-b:t1" "$B/fbs-c:t1"
draai "$B" t1 fbs-a fbs-b fbs-c
gelijk "drie gepubliceerde images slagen" 0 "$RC"
bevat_niet "zonder ontbrekend image geen foutmelding" '::error' "$UITVOER"

# --- ontbrekend: het enige, het middelste, en twee tegelijk --------------------------------------
maak_stub
draai "$B" t1 fbs-a
gelijk "een ontbrekend image faalt" 1 "$RC"
bevat "de melding noemt het ontbrekende image" '::error title=Image niet gepubliceerd::fbs-a ' "$UITVOER"
gelijk "een ontbrekend image krijgt drie pogingen" 3 "$(grep -c . <<<"$AANROEPEN")"

maak_stub "$B/fbs-a:t1" "$B/fbs-c:t1"
draai "$B" t1 fbs-a fbs-b fbs-c
gelijk "één ontbrekend image tussen gepubliceerde faalt" 1 "$RC"
bevat "het middelste ontbrekende image wordt genoemd" '::fbs-b ' "$UITVOER"
bevat_niet "een gepubliceerd image wordt niet als ontbrekend gemeld" '::fbs-c ' "$UITVOER"
bevat "het image na het ontbrekende wordt nog gecontroleerd" "Gepubliceerd: $B/fbs-c:t1" "$UITVOER"

maak_stub "$B/fbs-b:t1"
draai "$B" t1 fbs-a fbs-b fbs-c
gelijk "twee ontbrekende images falen" 1 "$RC"
bevat "elk ontbrekend image wordt gemeld, ook het eerste" '::fbs-a ' "$UITVOER"
bevat "elk ontbrekend image wordt gemeld, ook het laatste" '::fbs-c ' "$UITVOER"
bevat "de samenvatting noemt beide" 'Niet gepubliceerd: fbs-a fbs-c' "$UITVOER"

# --- de tag telt mee: hetzelfde image onder een oudere tag is niet gepubliceerd -------------------
maak_stub "$B/fbs-a:pr-7-abc1234"
draai "$B" pr-7-def5678 fbs-a
gelijk "een image onder alleen een andere tag faalt" 1 "$RC"
bevat "de melding noemt de verwachte tag" "$B/fbs-a:pr-7-def5678" "$UITVOER"

# --- een hik is geen ontbrekend image -------------------------------------------------------------
maak_stub "$B/fbs-a:t1"
echo "$B/fbs-a:t1" >"$werkmap/hikt"
draai "$B" t1 fbs-a
gelijk "een image na een mislukte opvraging slaagt alsnog" 0 "$RC"
gelijk "na een hik volgt precies één nieuwe poging" 2 "$(grep -c . <<<"$AANROEPEN")"

# --- niets te meten -------------------------------------------------------------------------------
maak_stub "$B/fbs-a:t1"
draai "$B" t1
gelijk "zonder image-namen faalt de controle" 1 "$RC"
bevat "en zegt dat hij niets meet" 'meet niets' "$UITVOER"
gelijk "zonder image-namen wordt het register niet gevraagd" '' "$AANROEPEN"

draai
gelijk "zonder argumenten faalt de controle" 1 "$RC"

# --- de vorm: wat een arm64-node start, en wat niet -----------------------------------------------
maak_stub "$B/fbs-a:t1"
zet_vorm "$B/fbs-a:t1" "$INDEX_AMD64"
draai "$B" t1 fbs-a
gelijk "een index met alleen amd64 en een attestation faalt" 1 "$RC"
bevat "de melding noemt het image en de oorzaak" '::error title=Image niet te starten op arm64::fbs-a ' "$UITVOER"
bevat "de melding noemt de uitweg" '--provenance=false' "$UITVOER"
bevat "de melding noemt wat er wél stond" 'als application/vnd.oci.image.index.v1+json met linux/amd64, unknown/unknown,' "$UITVOER"
bevat_niet "een verkeerde vorm is geen ontbrekend image" 'Image niet gepubliceerd' "$UITVOER"
gelijk "een verkeerde vorm wordt niet opnieuw opgevraagd" 1 "$(grep -c . <<<"$AANROEPEN")"

maak_stub "$B/fbs-a:t1"
zet_vorm "$B/fbs-a:t1" "$INDEX_BEIDE"
draai "$B" t1 fbs-a
gelijk "een index met amd64 en arm64 slaagt" 0 "$RC"

maak_stub "$B/fbs-a:t1"
zet_vorm "$B/fbs-a:t1" '{"schemaVersion":2,"manifests":[{"platform":{"os":"unknown","architecture":"unknown"}}]}'
draai "$B" t1 fbs-a
gelijk "een index met alleen een attestation faalt" 1 "$RC"

maak_stub "$B/fbs-a:t1"
zet_vorm "$B/fbs-a:t1" '{"schemaVersion":2,"manifests":[{"platform":{"os":"windows","architecture":"arm64"}}]}'
draai "$B" t1 fbs-a
gelijk "een index met arm64 voor een ander os faalt" 1 "$RC"

for onleesbaar in 'geen json' '{}' '[]'; do
  maak_stub "$B/fbs-a:t1"
  zet_vorm "$B/fbs-a:t1" "$onleesbaar"
  draai "$B" t1 fbs-a
  gelijk "een manifest '$onleesbaar' faalt" 1 "$RC"
done

bevat "een onleesbaar manifest heet zo in de melding" 'als een onleesbaar manifest,' "$UITVOER"

# Eén van elk: elk probleem wordt gemeld, en het goede image blijft goed.
maak_stub "$B/fbs-a:t1" "$B/fbs-b:t1"
zet_vorm "$B/fbs-b:t1" "$INDEX_AMD64"
draai "$B" t1 fbs-a fbs-b fbs-c
gelijk "een goed, een verkeerd gevormd en een ontbrekend image falen samen" 1 "$RC"
bevat "het goede image is gepubliceerd" "Gepubliceerd: $B/fbs-a:t1" "$UITVOER"
bevat "het verkeerd gevormde staat in de samenvatting" 'Niet te starten op arm64: fbs-b' "$UITVOER"
bevat "het ontbrekende staat in de samenvatting" 'Niet gepubliceerd: fbs-c' "$UITVOER"

# Zonder jq zou elk image als verkeerd gevormd lezen; dat moet als ontbrekende jq lezen.
mkdir -p "$werkmap/zonder-jq"
maak_stub "$B/fbs-a:t1"
RC=0
UITVOER=$(IMAGES_DOCKER="$werkmap/docker" IMAGES_WACHT=0 PATH="$werkmap/zonder-jq" "$(command -v bash)" "$SCRIPT" "$B" t1 fbs-a 2>&1) || RC=$?
gelijk "zonder jq faalt de controle" 1 "$RC"
bevat "en zegt dat jq ontbreekt" 'jq ontbreekt' "$UITVOER"
bevat_niet "zonder jq wordt geen image als verkeerd gemeld" 'niet te starten op arm64' "$UITVOER"

# --- kruiscontrole met deploy.yml -----------------------------------------------------------------
# Een YAML-parser en geen regex, om dezelfde reden als in workflow-jobs.py: GitHub leest YAML-vorm.
# De uitvoer is één regel per bevinding, `soort<TAB>waarde`; de bash-kant oordeelt.
kruis=$(python3 - "$DEPLOY_YML" "$CLEANUP_YML" <<'PY'
import json
import re
import sys

import yaml

with open(sys.argv[1], encoding="utf-8") as f:
    wf = yaml.safe_load(f)

try:
    lijst = json.loads(wf["env"]["EIGEN_IMAGES"])
except (KeyError, TypeError, ValueError) as e:
    print(f"onleesbaar\t{e}")
    sys.exit(0)

for sleutel, namen in lijst.items():
    print(f"sleutel\t{sleutel}\t{len(namen) if isinstance(namen, list) else -1}")
    for naam in namen if isinstance(namen, list) else []:
        print(f"bekend\t{naam}")

EXPR = re.compile(r"fromJSON\(env\.EIGEN_IMAGES\)\[(matrix\.service|'([^']+)')\]")
EIGEN = re.compile(r"\$\(eigen ([\w-]+)\)")
# Elke schrijfwijze van een eigen image buiten de build-jobs: inline in een componentenlijst, of als
# `$REGISTRY/$OWNER/<naam>:$TAG` in een run-blok.
REF = re.compile(r"/(fbs-[\w-]+):")
# Na de aanroep mag niets staan dat zijn exitcode wegneemt.
ONSCHADELIJK = re.compile(r"images-gepubliceerd\.sh[^\n]*(\|\||;|&)")


def pusht(stap):
    run = str(stap.get("run", ""))
    return "docker push" in run or "container-image.push=true" in run


for job_id, job in wf["jobs"].items():
    stappen = job.get("steps") or []
    bouwt = any(pusht(stap) for stap in stappen)
    laatste_push = max((i for i, stap in enumerate(stappen) if pusht(stap)), default=-1)

    for i, stap in enumerate(stappen):
        run = str(stap.get("run", ""))
        tekst = run + "\n" + "\n".join(f"{k}: {v}" for k, v in (stap.get("with") or {}).items())

        for naam in EIGEN.findall(run) + ([] if bouwt else REF.findall(tekst)):
            print(f"verwacht\t{naam}\t{job_id}")

        if "images-gepubliceerd.sh" not in run:
            continue

        # Een controlestap die zijn fout niet doorgeeft, is een groene stap die niets tegenhoudt.
        if stap.get("continue-on-error") not in (None, False):
            print(f"uitgeschakeld\t{job_id}\tcontinue-on-error")
        if str(stap.get("if", "success()")).replace("${{", "").replace("}}", "").strip() != "success()":
            print(f"uitgeschakeld\t{job_id}\tif: {stap.get('if')}")
        if ONSCHADELIJK.search(run):
            print(f"uitgeschakeld\t{job_id}\texitcode weggenomen")
        if i < laatste_push:
            print(f"uitgeschakeld\t{job_id}\tvóór de push")

        m = EXPR.search(str((stap.get("env") or {}).get("NAMEN", "")))
        if not m:
            print(f"zonder-lijst\t{job_id}")
        elif m.group(2):
            print(f"gecontroleerd\t{m.group(2)}\t{job_id}")
        else:
            for waarde in job.get("strategy", {}).get("matrix", {}).get("service", []):
                print(f"gecontroleerd\t{waarde}\t{job_id}")

# De opruiming van een preview noemt dezelfde images; een vergeten naam laat weesversies achter.
with open(sys.argv[2], encoding="utf-8") as f:
    opruim = yaml.safe_load(f)

for job in opruim["jobs"].values():
    for stap in job.get("steps") or []:
        for lijst in re.findall(r"for pkg in ([^;\n]+); do", str(stap.get("run", ""))):
            for naam in lijst.split():
                print(f"opgeruimd\t{naam}")
PY
) || { fout "de kruiscontrole kon deploy.yml niet lezen"; kruis=''; }

if grep -q '^onleesbaar' <<<"$kruis"; then
  fout "EIGEN_IMAGES in deploy.yml is geen JSON: $(grep '^onleesbaar' <<<"$kruis")"
fi

bekend=$(awk -F'\t' '$1 == "bekend" { print $2 }' <<<"$kruis" | sort)
sleutels=$(awk -F'\t' '$1 == "sleutel" { print $2 }' <<<"$kruis" | sort)
verwacht=$(awk -F'\t' '$1 == "verwacht" { print $2 }' <<<"$kruis" | sort -u)
gecontroleerd=$(awk -F'\t' '$1 == "gecontroleerd" { print $2 }' <<<"$kruis" | sort)

# Leeg is hier een kapotte meting, geen geslaagde: een parser die niets vindt, keurt alles goed.
[ -n "$bekend" ] && ok "EIGEN_IMAGES noemt images" || fout "EIGEN_IMAGES noemt geen enkel image; de kruiscontrole meet niets"
[ -n "$verwacht" ] && ok "de componentenlijsten noemen eigen images" || fout "geen eigen image gevonden in een componentenlijst; de kruiscontrole meet niets"

# Twee bekende vormen moeten ertussen zitten, anders is een van beide patronen stil gaan missen.
bevat "de componenten-stap van meta wordt gelezen" 'verwacht	fbs-demo-console	meta' "$kruis"
bevat "de inline componentenlijsten van de test-uitrol worden gelezen" 'verwacht	fbs-fsc-contract-bootstrap	deploy-test-magazijnen' "$kruis"

kruis_fouten=$mislukt

while IFS=$'\t' read -r soort sleutel aantal; do
  [ "$soort" = sleutel ] || continue
  [ "$aantal" -gt 0 ] || fout "EIGEN_IMAGES['$sleutel'] is geen lijst met namen"
done <<<"$kruis"

dubbel=$(uniq -d <<<"$bekend")
[ -z "$dubbel" ] || fout "deze images staan onder meer dan één build-job in EIGEN_IMAGES: $dubbel"

while IFS= read -r naam; do
  [ -n "$naam" ] || continue
  grep -qxF "$naam" <<<"$bekend" \
    || fout "de uitrol verwacht $naam, maar dat staat niet in EIGEN_IMAGES en geen build-job controleert het"
done <<<"$verwacht"

while IFS= read -r naam; do
  [ -n "$naam" ] || continue
  grep -qxF "$naam" <<<"$verwacht" \
    || fout "EIGEN_IMAGES noemt $naam, maar geen componentenlijst verwacht het"
done <<<"$bekend"

gelijk "elke sleutel van EIGEN_IMAGES wordt door precies één controlestap gelezen" "$sleutels" "$gecontroleerd"

if grep -q '^zonder-lijst' <<<"$kruis"; then
  fout "een controlestap leest zijn namen niet uit EIGEN_IMAGES: $(awk -F'\t' '$1 == "zonder-lijst" { print $2 }' <<<"$kruis")"
fi

while IFS=$'\t' read -r soort job reden; do
  [ "$soort" = uitgeschakeld ] || continue
  fout "de controlestap van $job houdt niets tegen ($reden); een ontbrekend image blijft dan groen"
done <<<"$kruis"

[ "$mislukt" -ne "$kruis_fouten" ] || ok "uitrol en build-jobs noemen dezelfde eigen images"

opgeruimd=$(awk -F'\t' '$1 == "opgeruimd" { print $2 }' <<<"$kruis" | sort)
gelijk "de opruiming van een preview kent dezelfde eigen images" "$bekend" "$opgeruimd"

# Elke build-job die naar ghcr pusht, draagt de controle — ook een die later bijkomt.
while IFS= read -r job; do
  [ -n "$job" ] || continue
  bevat "build-job $job controleert zijn publicatie" "gecontroleerd	" "$(grep -P "\t$job\$" <<<"$kruis" || true)"
done < <(python3 - "$DEPLOY_YML" <<'PY'
import sys

import yaml

with open(sys.argv[1], encoding="utf-8") as f:
    jobs = yaml.safe_load(f)["jobs"]

for job_id, job in jobs.items():
    runs = " ".join(str(s.get("run", "")) for s in job.get("steps") or [])
    if "docker push" in runs or "container-image.push=true" in runs:
        print(job_id)
PY
)

echo "ASSERTIES=$asserties"

if [ "$mislukt" -ne 0 ]; then
  exit 1
fi
