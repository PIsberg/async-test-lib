# Part 1b — Lemon Squeezy

Part of the [Licensing runbook](../LICENSING.md).

The `/newcustomerlicense` skill automates these steps. This section is the same process written
out, and the reference when something does not match.

## The one thing to get right: the domain

Validation binds the licence to the **domain of the buying address**. A purchase by
`ops@acme-corp.com` licenses everyone at `acme-corp.com`.

That makes the billing address a licensing decision, not an invoicing detail. Get it wrong and
the customer's developers are denied on their first run. Confirm the domain their engineers
actually use before you send the checkout link — a company that pays through
`accounts-payable@acme-holdings.example` but develops as `@acme-corp.com` has bought a licence
that covers nobody.

If a customer genuinely needs per-seat licensing instead, they set `-Dls.email.binding=exact` and
each developer buys under their own address.

## Step 1 — check the store can sell live

```bash
set -a; . ~/.config/deversity/lemonsqueezy.env; set +a
echo "$LS_TEST_MODE"
```

If this says `true`, the store is still pinned in test mode. Test-mode orders, customers and
keys are a **separate data set**; a test-mode key will not validate against a live store. The
product catalog itself is *shared* between modes (verified 2026-08-06), so nothing needs
re-creating — what keeps the store in test mode is **activation**: until Lemon Squeezy approves
identity verification (Settings → General → Store activation), the test-mode toggle is disabled
and every order is a test order. Fix the verification, then set `LS_TEST_MODE=false` in the
env file.

## Step 2 — send the checkout link

The product has one tier variant per team size, priced in EUR per year: 1–9 developers at 250,
10–49 at 900, 50–199 at 2,500, 200+ at 4,300. Pick the tier from the customer's developer count
and take its numeric variant id from the env file (`LS_VARIANT_ID_TIER_1_9`,
`LS_VARIANT_ID_TIER_10_49`, `LS_VARIANT_ID_TIER_50_199`, `LS_VARIANT_ID_TIER_200_PLUS`):

```
https://<LS_STORE_SUBDOMAIN>.lemonsqueezy.com/checkout/buy/<LS_VARIANT_UUID>
    ?enabled=<LS_VARIANT_ID_TIER_...>
    &checkout%5Bemail%5D=<their-billing-email>
    &checkout%5Bcustom%5D%5Bcompany%5D=<their-company-name>
```

`enabled` restricts the checkout to that one tier; omit it to let the customer pick. The 200+
price is 4,300 rather than a rounder number because Lemon Squeezy caps variant prices at the
USD 5,000 equivalent. OEM/redistribution (from 10,000) is negotiated and invoiced off-platform.

The customer pays. You do not enter their payment details for them.

## Step 3 — collect the key

**Store → Licenses** in the dashboard, or:

```bash
curl -sS "https://api.lemonsqueezy.com/v1/license-keys?filter[store_id]=$LS_STORE_ID" \
  -H "Authorization: Bearer $LS_API_KEY" \
  -H "Accept: application/vnd.api+json"
```

The key's status will be **`inactive`**. That is correct and it is valid — `inactive` means
"issued, never activated", and async-test-lib never calls the activation endpoint. A key only
becomes `active` if something activates it, which nothing here does.

## Step 4 — verify before you send

```bash
curl -sS -X POST https://api.lemonsqueezy.com/v1/licenses/validate \
  -H "Accept: application/json" \
  -d "license_key=<their-key>"
```

Check three things in the response:

| Field | Must be |
|---|---|
| `valid` | `true` |
| `meta.store_id` | equal to `<LS_STORE_ID>` |
| `meta.customer_email` | on the domain you are licensing |

The store check matters more than it looks. `/v1/licenses/validate` takes no credentials and
answers for **every store on Lemon Squeezy**, so `valid: true` on its own only means "this is
someone's key". `meta.store_id` is what makes it *yours*.

## If they bought with the wrong address

This will happen. Someone checks out with a personal address, or an accounts-payable one, and the
licence ends up scoped to a domain their developers do not use.

You do not need a refund or a second purchase. **`meta.customer_email` reflects the customer's
*current* email, not the address captured at checkout**, so editing the customer record re-scopes
the existing key:

**Store → Customers →** the customer **→ … → Edit profile →** change the email **→ Save changes**.

The next validate call returns the new address, and the same key immediately covers the new
domain. Verified against a live key: after the edit, a run under the new domain went from
`FREE_PROVIDER_EMAIL` to `LICENSE GRANTED: LICENSE_VALID`.

Two consequences worth holding on to:

- It is a **re-scoping tool**, so treat customer-email edits as a licensing change, not an
  administrative one. Moving a customer to a different domain silently moves who their licence
  covers.
- A customer who changes their own billing email changes their licence scope with it. If their
  developers stop being able to build after an address change, this is the first thing to check.

## Step 5 — log it

```bash
echo "$(date -I)  <company>  <domain>  <order-id>  renews:<date>" \
  >> ~/.config/deversity/customers.tsv
```
