#!/bin/sh
#
# Check a licence the way a customer's build will, locally.
#
#   ./verify-license.sh <license-key> <licensed-address>
#
# Runs the real @AsyncTest suite twice against the live Keygen account:
#
#   1. with the licensed address  -> must PASS
#   2. with a decoy address       -> must FAIL
#
# The second run is the point. A positive-only check passes just as happily when the
# gate never engaged at all — mock mode auto-activates in CI, and license.mock.mode
# defaults to true in pom.xml — so "it went green" proves nothing on its own. Only a
# run that goes red on a bad address proves the licence is actually being enforced.
#
# This cannot run in CI, and is not meant to: CI has no credentials and self-mocks.

set -eu

KEY="${1:-}"
OWNER="${2:-}"
if [ -z "$KEY" ] || [ -z "$OWNER" ]; then
    echo "usage: $0 <license-key> <licensed-address>" >&2
    exit 2
fi

ENV_FILE="$HOME/.config/deversity/keygen.env"
[ -r "$ENV_FILE" ] || { echo "missing $ENV_FILE — stop" >&2; exit 1; }
set -a; . "$ENV_FILE"; set +a
[ -n "${KEYGEN_ACCOUNT_ID:-}" ] || { echo "KEYGEN_ACCOUNT_ID not set" >&2; exit 1; }
[ -n "${KEYGEN_PRODUCT_ID:-}" ] || { echo "KEYGEN_PRODUCT_ID not set" >&2; exit 1; }

REPO=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
cd "$REPO"

# Mock mode would make both runs pass and tell us nothing. CI sets it implicitly.
unset CI || true
unset GITHUB_ACTIONS || true

TEST_CLASS=AsyncTestContextTest
REPORT="async-test-lib/target/surefire-reports/TEST-se.deversity.asynctest.$TEST_CLASS.xml"
DECOY="nobody-else@${OWNER#*@}"

run() {
    rm -f "$REPORT"
    set +e
    mvn -q -pl async-test-lib test -Dtest="$TEST_CLASS" \
        -Dlicense.mock.mode=false \
        -Dlicense.provider=keygen \
        -Dkeygen.account.id="$KEYGEN_ACCOUNT_ID" \
        -Dkeygen.product.id="$KEYGEN_PRODUCT_ID" \
        -Dlicense.key="$KEY" \
        -Dlicense.user.email="$1" \
        -Dsurefire.failIfNoSpecifiedTests=false >/dev/null 2>&1
    rc=$?
    set -e
    # Never read $? through a pipe: it reports the last stage, not Maven.
    return $rc
}

# Surefire stores the message XML-escaped, so cut at the first encoded newline.
reason() {
    grep -o 'LICENSE DENIED[^<"]*' "$REPORT" 2>/dev/null | head -1 | sed 's/&#10;.*//'
}

echo "repo    : $REPO"
echo "account : $KEYGEN_ACCOUNT_ID"
echo "address : $OWNER"
echo "decoy   : $DECOY"
echo

printf 'run 1/2  licensed address  ... '
if run "$OWNER"; then
    [ -r "$REPORT" ] || { echo "FAIL (green, but the test class never ran)"; exit 1; }
    echo "PASS  $(sed -n 's/.*\(tests="[0-9]*"\).*\(errors="[0-9]*"\).*/\1 \2/p' "$REPORT" | head -1)"
else
    echo "FAIL — this licence does not work. Do not send it."
    echo "  $(reason)"
    exit 1
fi

printf 'run 2/2  decoy address     ... '
if run "$DECOY"; then
    echo "FAIL"
    echo
    echo "The decoy address passed too, so the gate is NOT enforcing this licence." >&2
    echo "Most likely mock mode is on somewhere, or license.provider is not keygen." >&2
    exit 1
else
    echo "denied, as it must be"
fi

echo
echo "OK — the licence is valid AND the gate is genuinely enforcing it."
