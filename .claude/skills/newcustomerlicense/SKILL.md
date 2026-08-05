---
name: newcustomerlicense
description: Issue a commercial async-test-lib license to a customer company — build their checkout link, retrieve the license key once they have paid, and produce the exact build flags to send them. Use when the user asks to create, issue, sell or set up a license for a company or customer.
---

# Issue a customer license

Takes a **company name** and produces everything needed to get that company licensed and running.

> **Read this first, because it is not what you would guess:** Lemon Squeezy has **no API to
> create a license key**. The License Keys API exposes retrieve, update and list — no create.
> Keys are minted by *orders*. So "create a license for Acme" really means "create Acme's
> checkout, let them pay, then read back the key the order generated". Every step below follows
> from that.

## 0. Load the store details

They are deliberately outside every repo:

```bash
set -a; . ~/.config/deversity/lemonsqueezy.env; set +a
echo "store=$LS_STORE_ID variant=$LS_VARIANT_UUID test_mode=$LS_TEST_MODE"
```

If that file is missing, stop and tell the user — do not guess IDs, and never hard-code them
into the repo.

**If `LS_TEST_MODE=true`, stop and say so.** A test-mode key will not validate for a real
customer: test-mode orders live in a separate data set, and the store id a real customer would
be given will not match `meta.store_id` on a test key. Selling from a test-mode product produces
a licence that denies on the customer's first run.

## 1. Collect the inputs

Required:

| Input | Why it matters |
|---|---|
| **Company name** | Goes into checkout custom data, so the order is attributable |
| **Billing email domain** | **This is the licence scope.** Validation binds on the *domain* of the buying address, so every developer at that domain is covered — and nobody outside it is |

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
COMPANY="Acme Corp"
EMAIL="ops@acme-corp.com"

python - <<'EOF'
import os, urllib.parse
company = os.environ["COMPANY"]; email = os.environ["EMAIL"]
q = urllib.parse.urlencode({
    "checkout[email]": email,
    "checkout[custom][company]": company,
})
print(f"https://{os.environ['LS_STORE_SUBDOMAIN']}.lemonsqueezy.com/checkout/buy/{os.environ['LS_VARIANT_UUID']}?{q}")
EOF
```

This is the same URL shape `LemonSqueezyCheckout.buildCheckoutUrl(email, variantId, customData)`
produces in `common-license-lib`, so the library and this skill stay in agreement.

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

## 5. Record it

Append a line to the customer log (outside git, alongside the store details):

```bash
echo "$(date -I)  <company>  <billing-domain>  <order-id>  renews:<date>" \
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
