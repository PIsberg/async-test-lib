---
name: newcustomerlicense
description: Issue a commercial async-test-lib license to a customer company — build their checkout link, retrieve the license key once they have paid, and produce the exact build flags to send them. Use when the user asks to create, issue, sell or set up a license for a company or customer.
---

# Issue a customer license

Takes a **company name** and produces everything needed to get that company licensed and running.

## First: which provider did they buy through?

| They paid via | Keys come from | Follow |
|---|---|---|
| **Paddle** (deversity.se/pricing.html) | Keygen — we mint the key ourselves | [Path A](#path-a--paddle--keygen) |
| **Lemon Squeezy** | Lemon Squeezy — the order mints the key | [Path B](#path-b--lemon-squeezy) |

If the user does not say, ask. The two produce different flags and bind differently, so guessing
gets the customer denied on their first build.

> **Two facts that shape everything below.** Lemon Squeezy has **no API to create a license key** —
> keys are minted by *orders*, so its flow is "send checkout, wait for payment, read the key back".
> Paddle has **no license engine at all**, so on that path we mint the key ourselves through
> Keygen the moment we see the payment email.

---

## Path A — Paddle + Keygen

### A0. Load the details

```bash
set -a; . ~/.config/deversity/keygen.env; set +a
set -a; . ~/.config/deversity/paddle.env; set +a
echo "keygen account=$KEYGEN_ACCOUNT_ID product=$KEYGEN_PRODUCT_ID policy=$KEYGEN_POLICY_ID"
if [ -n "$KEYGEN_ADMIN_TOKEN" ]; then echo "admin token=set"; else echo "admin token=MISSING — stop"; fi
```

`KEYGEN_ADMIN_TOKEN` is an admin credential: operator machine only, never sent to the customer,
never committed, never echoed in full.

### A1. Confirm the payment

Check the Paddle transaction is **Complete** (`vendors.paddle.com` → Transactions), or work from
the `transaction.completed` notification email. Do not issue a key on the strength of a customer
saying they paid.

### A2. Agree the licensed address — do not skip this

Keygen binds the licence to **one exact address**, not a domain. Measured live 2026-08-07: a
colleague at the same company domain is denied `USER_SCOPE_MISMATCH`.

So ask: *which single address will your builds present?* Push for something durable and shared —
`licence@acme-corp.com`, a team alias, or the tech lead — never a personal address that leaves
with the person. That address goes in their build config once; individual developers do not use
their own.

### A3. Issue the licence

```bash
export ATL_OWNER_EMAIL="licence@acme-corp.com"

jshell --class-path "$(ls ~/.m2/repository/se/deversity/common/common-license-lib/*/common-license-lib-*.jar | grep -v sources | grep -v javadoc | tail -1)" - <<'JSHELL'
import se.deversity.common.license.keygen.KeygenIssuer;
import java.net.URI; import java.net.http.HttpClient; import java.time.Duration;
var issuer = new KeygenIssuer(HttpClient.newHttpClient(), System.getenv("KEYGEN_ACCOUNT_ID"),
    System.getenv("KEYGEN_ADMIN_TOKEN"), URI.create("https://api.keygen.sh"), Duration.ofSeconds(20));
System.out.println("KEY=" + issuer.issueLicense(System.getenv("KEYGEN_POLICY_ID"),
    System.getenv("ATL_OWNER_EMAIL")));
JSHELL
```

`issueLicense` creates the Keygen user if it does not exist, then a licence owned by them under
the annual policy (365 days, expiring keys deny).

### A4. Verify the way the customer's build will

```bash
curl -sS -X POST   "https://api.keygen.sh/v1/accounts/$KEYGEN_ACCOUNT_ID/licenses/actions/validate-key"   -H "Content-Type: application/vnd.api+json" -H "Accept: application/vnd.api+json"   -d "{\"meta\":{\"key\":\"<their-key>\",\"scope\":{\"user\":\"$ATL_OWNER_EMAIL\",\"product\":\"$KEYGEN_PRODUCT_ID\"}}}"
```

`meta.valid` must be `true` and `meta.code` `VALID`. The scope key is `user` — Keygen rejects
`scope.email` with HTTP 400. Send no `Authorization` header here (as the customer's build does) or
a real token; a made-up one 401s before the licence is read.

### A5. Send their flags

```
-Dkeygen.account.id=<KEYGEN_ACCOUNT_ID>
-Dkeygen.product.id=<KEYGEN_PRODUCT_ID>
-Dlicense.key=<their key>
-Dlicense.user.email=<the licensed address>
```

`license.provider` defaults to `keygen`, so it can be omitted. Say plainly in the email that
`license.user.email` must be that exact address for every developer and in CI. The fuller block is
in `docs/LICENSING.md` §"What to send the customer".

### A6. Log it

```bash
echo "$(date -I)  <company>  <licensed-address>  paddle  <transaction-id>  renews:<date>"   >> ~/.config/deversity/customers.tsv
```

**Renewal is not automatic on this path.** Paddle rebills the subscription, but nothing extends
the Keygen licence — the two systems are not connected. When the renewal notification arrives,
extend the licence's expiry in Keygen, or their build fails a year after they bought.

---

## Path B — Lemon Squeezy

### 0. Load the store details

They are deliberately outside every repo:

```bash
set -a; . ~/.config/deversity/lemonsqueezy.env; set +a
echo "store=$LS_STORE_ID variant=$LS_VARIANT_UUID test_mode=$LS_TEST_MODE"
echo "tiers: 1-9=$LS_VARIANT_ID_TIER_1_9 10-49=$LS_VARIANT_ID_TIER_10_49 50-199=$LS_VARIANT_ID_TIER_50_199 200+=$LS_VARIANT_ID_TIER_200_PLUS"
```

If that file is missing, stop and tell the user — do not guess IDs, and never hard-code them
into the repo.

**If `LS_TEST_MODE=true`, stop and say so.** A test-mode key will not validate for a real
customer: test-mode orders live in a separate data set, and the store id a real customer would
be given will not match `meta.store_id` on a test key. The product catalog is shared between
modes, so nothing needs re-creating — the flag stays `true` while the store is unactivated
(identity verification pending or rejected under Settings → General → Store activation), because
Lemon Squeezy pins an unactivated store in test mode. Selling in that state produces a licence
that denies on the customer's first run.

## 1. Collect the inputs

Required:

| Input | Why it matters |
|---|---|
| **Company name** | Goes into checkout custom data, so the order is attributable |
| **Billing email domain** | **This is the licence scope.** Validation binds on the *domain* of the buying address, so every developer at that domain is covered — and nobody outside it is |
| **Developer count** | Picks the tier variant: 1–9 → €250/yr, 10–49 → €900/yr, 50–199 → €2,500/yr, 200+ → €4,300/yr (Lemon Squeezy caps prices at the USD 5,000 equivalent). OEM (from €10,000) is negotiated — invoice it off-platform |

Ask for the billing email if the user only gave a company name. The domain is not cosmetic: it
is the thing that decides who can run the library. A company that buys as `ops@acme-corp.com`
licenses `acme-corp.com`, so a contractor on `@gmail.com` is not covered by it.

Watch for the trap: if the company's billing address is on a **free mail provider**
(`@gmail.com`, `@outlook.com`, …), the gate lets that address through for free *without any
key at all*, because free-provider addresses are treated as non-commercial users. Point this out
rather than selling them a licence that their own address makes redundant.

## 2. Build the checkout link

No API call needed — this is a plain URL:

```bash
export COMPANY="Acme Corp"
export EMAIL="ops@acme-corp.com"
export TIER_ID="$LS_VARIANT_ID_TIER_10_49"   # from the developer count

python - <<'EOF'
import os, urllib.parse
q = urllib.parse.urlencode({
    "enabled": os.environ["TIER_ID"],
    "checkout[email]": os.environ["EMAIL"],
    "checkout[custom][company]": os.environ["COMPANY"],
})
print(f"https://{os.environ['LS_STORE_SUBDOMAIN']}.lemonsqueezy.com/checkout/buy/{os.environ['LS_VARIANT_UUID']}?{q}")
EOF
```

`enabled` restricts the checkout page to the one tier the customer is buying; omit it to show
all three and let them pick. The base URL is the same shape
`LemonSqueezyCheckout.buildCheckoutUrl(email, variantId, customData)` produces in
`common-license-lib` — `enabled` is a storefront display filter the library never sees.

Send that link to the customer. Do not attempt to pay on their behalf, and never enter card
details for them.

## 3. Read back the issued key

Once they have paid. Needs `LS_API_KEY` (the validate endpoint does not, but this lookup does):

```bash
curl -sS "https://api.lemonsqueezy.com/v1/license-keys?filter[store_id]=$LS_STORE_ID" \
  -H "Authorization: Bearer $LS_API_KEY" \
  -H "Accept: application/vnd.api+json"
```

Match the customer by email, and take `attributes.key`. The dashboard equivalent is
**Store → Licenses**.

A freshly issued key has status **`inactive`** — that is normal and it is valid. `inactive`
means "never activated", and async-test-lib never calls `/activate`. Do not "fix" this.

## 4. Hand the customer their flags

Fill the values in and give them the block from `docs/LICENSING.md` §"What to send the customer".
The four things they need:

```
-Dlicense.provider=lemonsqueezy
-Dls.store.id=<LS_STORE_ID>
-Dlicense.key=<their key>
-Dlicense.user.email=<any address on the licensed domain>
```

Sanity-check the licence before sending it, using the customer's own domain:

```bash
curl -sS -X POST https://api.lemonsqueezy.com/v1/licenses/validate \
  -H "Accept: application/json" \
  -d "license_key=<their key>"
```

Confirm in the response that `valid` is `true`, that `meta.store_id` equals `$LS_STORE_ID`, and
that `meta.customer_email` is on the domain you promised them. If the domain differs from what
the customer's developers actually use, the licence will deny on their first run — fix it now,
not after they complain.

**If the domain is wrong, do not issue a second licence.** `meta.customer_email` reports the
customer's *current* address, not the one captured at checkout, so editing the customer record
re-scopes the existing key: **Store → Customers →** the customer **→ … → Edit profile**. The next
validate call returns the new address and the same key starts covering the new domain. Treat that
as a licensing change rather than an administrative one — it moves who the licence covers.

## 5. Record it

Append a line to the customer log (outside git, alongside the store details):

```bash
echo "$(date -I)  <company>  <billing-domain>  <tier>  <order-id>  renews:<date>" \
  >> ~/.config/deversity/customers.tsv
```

Renewal is Lemon Squeezy's job — the subscription rebills yearly and the key's `expires_at`
moves with it. What is *not* automatic is noticing a failed rebill, so keep this log.

## Do not

- **Do not publish, merge or tag anything** as part of issuing a licence. This skill touches no
  repository.
- **Do not enter payment details** on a customer's behalf.
- **Do not commit** store ids, variant UUIDs, API keys or customer records.
- **Do not promise technical enforcement.** `-Dlicense.mock.mode=true` disables the gate
  entirely, and `-Dlicense.user.email` is self-asserted. The licence is a legal instrument that
  the library helps honest customers comply with; it is not DRM. Price and pitch accordingly.
