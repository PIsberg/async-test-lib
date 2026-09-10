#!/usr/bin/env bash
#
# Verifies the CycloneDX SBOM the build just produced.
#
# sbom.yml and publish.yml used to generate a BOM and upload whatever came out. An empty BOM, a
# BOM missing a module, or a missing file was green: upload-artifact warns on a path that does not
# exist and exits 0, and publish.yml's `if [ -f ]` guard skips a file that was never written. That
# is how bom.json stayed absent from every release up to v1.11.2 while both workflows named it.
#
# The checks below are the properties a consumer of the SBOM relies on, asserted against the
# generated files rather than against the plugin configuration meant to produce them.
#
# Usage: verify-sbom.sh [target-dir]   (default: target)
set -euo pipefail

TARGET_DIR="${1:-target}"
XML="${TARGET_DIR}/bom.xml"
JSON="${TARGET_DIR}/bom.json"

# Every artifact a consumer resolves at runtime, plus the three published modules. A purl that
# stops appearing here means the aggregate BOM stopped covering a module or a scope.
REQUIRED_PURLS=(
  "pkg:maven/se.deversity.async-test-lib/async-test-lib@"
  "pkg:maven/se.deversity.async-test-lib/async-test-agent@"
  "pkg:maven/se.deversity.async-test-lib/async-test-analysis@"
  "pkg:maven/org.slf4j/slf4j-api@"
  "pkg:maven/net.bytebuddy/byte-buddy@"
  "pkg:maven/org.ow2.asm/asm@"
)

# A floor, not a pin: the aggregate carried 24 components at v1.11.2, and a BOM collapsed to a
# handful is the failure this catches. Dependency churn moves the real number, so the floor sits
# well below it and only a collapse trips it.
MIN_COMPONENTS=15

fail() {
  echo "SBOM verification failed: $1" >&2
  exit 1
}

for file in "$XML" "$JSON"; do
  [ -f "$file" ] || fail "$file was not generated (cyclonedx outputFormat must be 'all')"
  [ -s "$file" ] || fail "$file is empty"
done

grep -q 'xmlns="http://cyclonedx.org/schema/bom/1.6"' "$XML" \
  || fail "$XML is not CycloneDX 1.6"

# python3 on a Windows PATH is often the Microsoft Store alias: it answers command -v and then
# exits without an interpreter, so each candidate is asked to run something before it is used.
PYTHON=""
for candidate in python3 python; do
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c "import sys" >/dev/null 2>&1; then
    PYTHON="$candidate"
    break
  fi
done
[ -n "$PYTHON" ] || fail "no working python3 on PATH to parse $JSON"

"$PYTHON" - "$JSON" "$MIN_COMPONENTS" "${REQUIRED_PURLS[@]}" <<'PY'
import json
import sys

path, floor = sys.argv[1], int(sys.argv[2])
required = sys.argv[3:]

try:
    with open(path, encoding="utf-8") as handle:
        bom = json.load(handle)
except (OSError, ValueError) as error:
    sys.exit("SBOM verification failed: %s does not parse as JSON (%s)" % (path, error))

if bom.get("specVersion") != "1.6":
    sys.exit("SBOM verification failed: %s is specVersion %r, expected 1.6"
             % (path, bom.get("specVersion")))

if not bom.get("serialNumber"):
    sys.exit("SBOM verification failed: %s carries no serialNumber" % path)

components = bom.get("components", [])
if len(components) < floor:
    sys.exit("SBOM verification failed: %s lists %d components, floor is %d"
             % (path, len(components), floor))

purls = [component.get("purl", "") for component in components]
purls.append(bom.get("metadata", {}).get("component", {}).get("purl", ""))

missing = [prefix for prefix in required
           if not any(purl.startswith(prefix) for purl in purls)]
if missing:
    sys.exit("SBOM verification failed: %s is missing %s" % (path, ", ".join(missing)))

print("SBOM ok: %s, %d components, specVersion %s"
      % (path, len(components), bom["specVersion"]))
PY

# The XML is the format the releases have carried all along; check it independently rather than
# trusting that the two formats agree.
for purl in "${REQUIRED_PURLS[@]}"; do
  grep -qF "<purl>${purl}" "$XML" || fail "$XML is missing ${purl} in the XML BOM"
done

echo "SBOM ok: $XML carries all ${#REQUIRED_PURLS[@]} required components"
