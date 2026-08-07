# Licensing async-test-lib to a customer

How to issue a commercial licence, what to send the customer, what happens when it expires, and
how renewal works.

Two audiences, kept apart on purpose:

- **[Part 1 — for you](#part-1--issuing-a-licence)**: the operator runbook.
- **[Part 2 — for the customer](#part-2--what-to-send-the-customer)**: copy-paste and send.

There are **two ways a licence can be sold**, and they behave differently. Pick the section that
matches how the customer paid:

| Sold through | Keys minted by | Customer sets | Covered by |
|---|---|---|---|
| **Paddle** (deversity.se) | Keygen | `-Dlicense.provider=keygen` | [Part 1a](#part-1a--paddle--keygen) |
| **Lemon Squeezy** | Lemon Squeezy | `-Dlicense.provider=lemonsqueezy` | [Part 1b](#part-1b--lemon-squeezy) |

Ids, tokens and API keys are **not** in this repository. They live in
`~/.config/deversity/paddle.env`, `keygen.env` and `lemonsqueezy.env`. Placeholders below in
`<ANGLE_BRACKETS>` come from there.

---

## Read this before you sell anything

Three properties of this system that decide how you should price and pitch it.

**1. The gate is not DRM, and cannot be.** `-Dlicense.mock.mode=true` disables licence checking
completely, and the library prints that flag in its own denial message. `-Dlicense.user.email` is
whatever the user types. The library is a compliance aid for customers who intend to comply — the
enforceable instrument is the licence agreement, not the code. This is the normal open-core
arrangement and matches [SELLING_THIS_LIBRARY.md](https://github.com/PIsberg/common-license-lib)
in `common-license-lib`, which says enforcement here is legal, not technical.

**2. Free-mail addresses need no licence.** A developer running as `someone@gmail.com` is
classified as a non-commercial user and passes without a key. Only commercial email domains are
asked for one. So the addressable customer is a company using its own domain.

**3. The two providers scope a licence differently, and this is the thing most likely to bite
you.** Lemon Squeezy binds to the *domain* of the buying address, so everyone at `acme-corp.com`
is covered. Keygen binds to the *exact owner address* on the licence. Measured against the live
Keygen API on 2026-08-07 with a real key issued to `e2e-test@example-corp.com`:

| `-Dlicense.user.email` | Result |
|---|---|
| `e2e-test@example-corp.com` (the owner) | `LICENSE GRANTED` |
| `someone-else@example-corp.com` (same company!) | `DENIED — USER_SCOPE_MISMATCH` |
| `mallory@evil-corp.com` | `DENIED — USER_SCOPE_MISMATCH` |

So on the Paddle/Keygen path a team still buys **one key**, but every developer's build must
present the **same licensed address**, set once in the shared build config — not each developer's
own address. Tell customers this explicitly; it is not what "covers your whole team" usually
implies.

---

# Part 1a — Paddle + Keygen

This is the path for anyone who buys from **deversity.se/pricing.html**. Paddle takes the money;
it has no licence engine at all, so Keygen mints and validates the key. Fulfilment is a person:
Paddle emails you, you issue the key.

## Step 1 — the customer buys

They pick a tier on <https://deversity.se/pricing.html> and pay. Paddle.js opens the checkout with
the live price id for that tier. Nothing reaches them automatically except Paddle's own receipt
and subscription emails — **neither contains a licence key**.

## Step 2 — you get the notification

Paddle's notification destination emails `transaction.completed` to the operator address. That
email is the trigger for everything below. Nothing polls, and nothing retries: if you miss it, the
customer waits.

## Step 3 — agree the licensed address before issuing

Because Keygen binds to one exact address (see above), ask the customer which address their builds
will present. A shared, durable address is best — `licence@acme-corp.com`, or the team lead. Avoid
a personal address that leaves when the person does.

## Step 4 — issue the key

```bash
set -a; . ~/.config/deversity/keygen.env; set +a
```

The `/newcustomerlicense` skill does this. By hand it is `KeygenIssuer` from `common-license-lib`:
ensure a Keygen user for the licensed address, then create a licence under
`$KEYGEN_POLICY_ID` owned by that user. The policy is 365 days, expiring keys deny, and renewal
extends from expiry.

## Step 5 — verify before you send

Validate the key exactly as the customer's build will, using the licensed address:

```bash
set -a; . ~/.config/deversity/keygen.env; set +a
curl -sS -X POST   "https://api.keygen.sh/v1/accounts/$KEYGEN_ACCOUNT_ID/licenses/actions/validate-key"   -H "Content-Type: application/vnd.api+json" -H "Accept: application/vnd.api+json"   -d "{\"meta\":{\"key\":\"<their-key>\",\"scope\":{\"user\":\"<licensed-address>\",\"product\":\"$KEYGEN_PRODUCT_ID\"}}}"
```

`meta.valid` must be `true` and `meta.code` must be `VALID`. Two traps worth knowing, both found
the hard way:

- The scope key is `user`, **not** `email`. Keygen rejects `scope.email` with HTTP 400
  `unpermitted parameter`.
- `validate-key` is a public endpoint, but a *made-up* bearer token is rejected with 401 before the
  licence is evaluated. Send a real token or none at all.

## Step 6 — send them their flags

Use the Keygen block in [Part 2](#part-2--what-to-send-the-customer).

## Step 7 — log it

```bash
echo "$(date -I)  <company>  <licensed-address>  paddle  <transaction-id>  renews:<date>"   >> ~/.config/deversity/customers.tsv
```

Renewal is Paddle's job — the subscription rebills yearly. The Keygen licence does **not** extend
itself when Paddle rebills: nothing connects the two today. Watch for the renewal notification and
extend the licence's expiry in Keygen, or the customer's build starts failing a year after they
bought.

---

# Part 1b — Lemon Squeezy

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

---

# Part 2 — what to send the customer

Everything below is written for them. Fill in the three `<...>` values and send.

---

**Your async-test-lib commercial licence**

You have one key for the whole team. You do not need one key per developer.

### If you bought through deversity.se (Keygen key)

**Add these flags to your test runs:**

```
-Dkeygen.account.id=<KEYGEN_ACCOUNT_ID>
-Dkeygen.product.id=<KEYGEN_PRODUCT_ID>
-Dlicense.key=<your-license-key>
-Dlicense.user.email=<the licensed address>
```

`license.provider` defaults to `keygen`, so you can leave it out.

> **`license.user.email` must be the exact address the licence was issued to** — the one we agreed
> when you bought. It is not "each developer's own address": a colleague at the same company
> domain is rejected with `LICENSE_INVALID`. Set it once in your shared build config, not
> per-machine. Tell us if you need it moved to a different address; we can re-point the licence
> without issuing a new key.

### If you bought through Lemon Squeezy

**Add these four flags to your test runs:**

```
-Dlicense.provider=lemonsqueezy
-Dls.store.id=<LS_STORE_ID>
-Dlicense.key=<your-license-key>
-Dlicense.user.email=<your work email>
```

Here `license.user.email` can be **any** address on your company domain — the person running the
build — because this licence is bound to the domain rather than to one address.

**Maven** — in your `pom.xml`, so nobody has to remember the flags (Keygen shown; for Lemon
Squeezy swap the three provider lines for `license.provider=lemonsqueezy` and `ls.store.id`):

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <systemPropertyVariables>
      <keygen.account.id>${env.ATL_KEYGEN_ACCOUNT}</keygen.account.id>
      <keygen.product.id>${env.ATL_KEYGEN_PRODUCT}</keygen.product.id>
      <license.key>${env.ATL_LICENSE_KEY}</license.key>
      <license.user.email>${env.ATL_LICENSE_EMAIL}</license.user.email>
    </systemPropertyVariables>
  </configuration>
</plugin>
```

**Gradle** — in `build.gradle.kts`:

```kotlin
tasks.test {
    systemProperty("keygen.account.id", System.getenv("ATL_KEYGEN_ACCOUNT") ?: "")
    systemProperty("keygen.product.id", System.getenv("ATL_KEYGEN_PRODUCT") ?: "")
    systemProperty("license.key", System.getenv("ATL_LICENSE_KEY") ?: "")
    systemProperty("license.user.email", System.getenv("ATL_LICENSE_EMAIL") ?: "")
}
```

**In CI**, put the key in your secret store and expose it as `ATL_LICENSE_KEY`. It is not a
password — it identifies your subscription, not an account — but treat it like any other secret.

**Checking it worked.** On a licensed run the log contains:

```
LICENSE GRANTED: LICENSE_VALID provider=KEYGEN
```

(`provider=LEMONSQUEEZY` if you bought that way.)

If licensing is misconfigured the build fails with `SecurityException: LICENSE DENIED: <reason>`,
and the message lists the flags. Common reasons:

| Reason | What happened |
|---|---|
| `LICENSE_REQUIRED` | No key supplied, on a commercial email domain |
| `LICENSE_NOT_FOUND` | Key does not exist — check for a copy-paste truncation |
| `LICENSE_INVALID` | Key is real but not for this product/store, or `license.user.email` is not the licensed address (Keygen reports `USER_SCOPE_MISMATCH`) |
| `LICENSE_EXPIRED` | Subscription lapsed — see below |
| `NETWORK_ERROR` | Could not reach `api.keygen.sh` / `api.lemonsqueezy.com` |

If your build agents cannot reach the internet, `NETWORK_ERROR` fails the build by default. Ask
us about the fail-open option rather than disabling the gate.

**When the licence expires.** The subscription rebills yearly. When it lapses, the key's status
becomes `expired` and runs fail with:

```
LICENSE DENIED: LICENSE_EXPIRED
```

It fails the build — it does not silently downgrade or skip tests. Nothing is deleted, and no
previously published artifact is affected; only new `@AsyncTest` runs are blocked.

**Renewal.** Automatic. Lemon Squeezy charges the card on file each year and emails a receipt;
the key's expiry moves forward on its own and you do nothing. Your key does not change, so no
build configuration changes either.

If the card is declined you get a payment-failure email with a retry window before the
subscription is cancelled. Updating the card from the link in that email keeps the **same key**
working — recovering a lapsed subscription extends the existing licence rather than issuing a
new one.

One case does change your key: if you let the subscription cancel outright and later buy again,
that is a **new order and therefore a new licence key**, and you will need to update
`license.key`. Renewing before cancellation avoids this entirely.

Manage or cancel from the "Manage your subscription" link on any receipt. If you cancel, the
licence stays valid until the end of the paid term and then goes `expired`.

---

## For maintainers: what is actually wired up

`LicenseGuard` resolves these system properties. The `ls.*` ones are ignored on the Keygen path
and the `keygen.*` ones on the LemonSqueezy path.

| Property | Meaning | Default |
|---|---|---|
| `license.provider` | `keygen` or `lemonsqueezy` | `keygen` |
| `keygen.account.id` | Keygen account UUID; **required** for Keygen | `dummy-account` |
| `keygen.product.id` | Keygen product UUID; scopes the check to our product | `dummy-prod` |
| `keygen.api.key` | Optional Keygen token. **Customers leave this unset** — `validate-key` is public, and a placeholder token makes Keygen answer 401 before it looks at the licence | unset |
| `ls.store.id` | Numeric store id; **required** for LemonSqueezy | — |
| `ls.product.id` | Optional narrower product scope | unset |
| `ls.email.binding` | `domain` or `exact` | `domain` |
| `ls.api.base.uri` | Override the API host; for tests | LemonSqueezy |
| `license.key` | The customer's key | — |
| `license.user.email` | Address the run is attributed to. Keygen matches it against the licence **owner**; LemonSqueezy against the buying **domain** | `""` |
| `license.mock.mode` | Bypasses the gate entirely | `false` |

**Mock mode auto-activates in CI when no credentials are present** (`GITHUB_ACTIONS` or `CI` set,
and no `keygen.api.key` / no LemonSqueezy store+key). That is deliberate — it keeps our own CI and
contributors unblocked — but it also means a test that asserts a *real* denial cannot be written
in this repo: it would pass by mocking rather than by exercising the gate. Regression coverage for
the validators lives in `common-license-lib` instead.

`license.provider`, `ls.store.id`, `ls.product.id`, `ls.email.binding` and `license.key` are all
part of `LicenseGuard`'s cache fingerprint, so changing any of them within a JVM recomputes the
decision instead of reusing a cached grant.

See [CONFIGURATION.md](CONFIGURATION.md) for the full `@AsyncTest` surface.
