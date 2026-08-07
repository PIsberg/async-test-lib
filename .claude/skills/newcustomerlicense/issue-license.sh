#!/bin/sh
#
# Issue one commercial async-test-lib licence and prove it works before it is sent.
#
#   ./issue-license.sh "Acme Corp" licence@acme-corp.com
#
# Mints the key through Keygen, then validates it exactly the way the customer's build
# will — including a negative probe, because a licence that validates for everyone is
# indistinguishable from one that validates for nobody until a customer complains.
#
# Reads ~/.config/deversity/keygen.env. Nothing here contains an id or a credential.

set -eu

COMPANY="${1:-}"
OWNER="${2:-}"
if [ -z "$COMPANY" ] || [ -z "$OWNER" ]; then
    echo "usage: $0 <company> <licensed-address>" >&2
    echo "example: $0 \"Acme Corp\" licence@acme-corp.com" >&2
    exit 2
fi

ENV_FILE="$HOME/.config/deversity/keygen.env"
[ -r "$ENV_FILE" ] || { echo "missing $ENV_FILE — do not guess ids, stop here" >&2; exit 1; }
set -a; . "$ENV_FILE"; set +a

for v in KEYGEN_ACCOUNT_ID KEYGEN_PRODUCT_ID KEYGEN_POLICY_ID KEYGEN_ADMIN_TOKEN; do
    eval "val=\${$v:-}"
    [ -n "$val" ] || { echo "$v is not set in $ENV_FILE — stop" >&2; exit 1; }
done

# A free-provider address is already licence-free under the gate, so selling one a key
# is selling nothing. Catch it before the money changes hands, not after.
case "$OWNER" in
    *@gmail.com|*@googlemail.com|*@outlook.com|*@hotmail.com|*@live.com|*@yahoo.com|*@icloud.com|*@proton.me|*@protonmail.com)
        echo "REFUSING: $OWNER is a free mail provider." >&2
        echo "The gate already lets those through without any key — they need a company address." >&2
        exit 1;;
esac

# Highest version wins, and sort -V so 0.10.0 beats 0.5.0. Override with ATL_LICENSE_LIB_JAR.
JAR="${ATL_LICENSE_LIB_JAR:-}"
if [ -z "$JAR" ]; then
    JAR=$(find "$HOME/.m2/repository/se/deversity/common/common-license-lib" \
              -name 'common-license-lib-*.jar' 2>/dev/null \
          | grep -vE 'sources|javadoc' | sort -V | tail -1)
fi
[ -n "$JAR" ] && [ -r "$JAR" ] || {
    echo "no common-license-lib jar found; build it or set ATL_LICENSE_LIB_JAR" >&2; exit 1; }
echo "issuer jar : $JAR"
echo "account    : $KEYGEN_ACCOUNT_ID"
echo "company    : $COMPANY"
echo "address    : $OWNER"
echo

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ATL_OWNER_EMAIL="$OWNER"; export ATL_OWNER_EMAIL

KEY=$(jshell -q --class-path "$JAR" "$HERE/issue-license.jsh" \
      | sed -n 's/^KEY=//p' | tr -d '\r')
[ -n "$KEY" ] || { echo "issuance produced no key — nothing was sent" >&2; exit 1; }

# Validate with no Authorization header, which is what the customer's build sends.
validate() {
    curl -sS -X POST \
        "https://api.keygen.sh/v1/accounts/$KEYGEN_ACCOUNT_ID/licenses/actions/validate-key" \
        -H 'Content-Type: application/json' -H 'Accept: application/json' \
        -d "{\"meta\":{\"key\":\"$KEY\",\"scope\":{\"user\":\"$1\",\"product\":\"$KEYGEN_PRODUCT_ID\"}}}"
}

DECOY="nobody-else@${OWNER#*@}"
echo "$(validate "$OWNER")"   | grep -q '"valid":true'  || {
    echo "FAILED: the licensed address does not validate. Do not send this key." >&2; exit 1; }
echo "$(validate "$DECOY")"   | grep -q '"valid":false' || {
    echo "FAILED: $DECOY also validates — the licence is not bound. Do not send." >&2; exit 1; }

echo "verified   : $OWNER accepted, $DECOY rejected"
echo
echo "=== send these flags to $COMPANY ==============================="
cat <<FLAGS
-Dkeygen.account.id=$KEYGEN_ACCOUNT_ID
-Dkeygen.product.id=$KEYGEN_PRODUCT_ID
-Dlicense.key=$KEY
-Dlicense.user.email=$OWNER
FLAGS
echo "================================================================"
echo
echo "Tell them license.user.email must be that exact address for every developer"
echo "and in CI. It is not a per-person field, and a colleague's own address is denied."
echo
echo "Then log it (renewal is NOT automatic — see the skill):"
echo "  echo \"\$(date -I)  $COMPANY  $OWNER  paddle  <transaction-id>  renews:\$(date -I -d '+1 year' 2>/dev/null || echo '<+1y>')\" >> ~/.config/deversity/customers.tsv"
