# Licensing async-test-lib to a customer

How to issue a commercial licence, what to send the customer, what happens when it expires, and
how renewal works.

Two audiences, kept apart on purpose:

- **[Part 1 — for you](#part-1--issuing-a-licence)**: the operator runbook.
- **[Part 2 — for the customer](#part-2--what-to-send-the-customer)**: copy-paste and send.

Store ids, variant UUIDs and API keys are **not** in this repository. They live in
`~/.config/deversity/lemonsqueezy.env`. Placeholders below in `<ANGLE_BRACKETS>` come from there.

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

**3. A licence covers an email domain, not a person.** See below.

---

# Part 1 — issuing a licence

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

## Step 1 — check you are not in test mode

```bash
set -a; . ~/.config/deversity/lemonsqueezy.env; set +a
echo "$LS_TEST_MODE"
```

If this says `true`, the product was created with the dashboard's test-mode toggle on. Test-mode
orders, customers and keys are a **separate data set**; a test-mode key will not validate against
a live store. Recreate the product with test mode off and update the env file before selling.

## Step 2 — send the checkout link

```
https://<LS_STORE_SUBDOMAIN>.lemonsqueezy.com/checkout/buy/<LS_VARIANT_UUID>
    ?checkout%5Bemail%5D=<their-billing-email>
    &checkout%5Bcustom%5D%5Bcompany%5D=<their-company-name>
```

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

Your licence covers everyone with an `@<your-domain>` email address. You do not need one key per
developer.

**Add these four flags to your test runs:**

```
-Dlicense.provider=lemonsqueezy
-Dls.store.id=<LS_STORE_ID>
-Dlicense.key=<your-license-key>
-Dlicense.user.email=<your work email>
```

`license.user.email` can be any address on your company domain — the person running the build.

**Maven** — in your `pom.xml`, so nobody has to remember the flags:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <systemPropertyVariables>
      <license.provider>lemonsqueezy</license.provider>
      <ls.store.id>${env.ATL_STORE_ID}</ls.store.id>
      <license.key>${env.ATL_LICENSE_KEY}</license.key>
      <license.user.email>${env.ATL_LICENSE_EMAIL}</license.user.email>
    </systemPropertyVariables>
  </configuration>
</plugin>
```

**Gradle** — in `build.gradle.kts`:

```kotlin
tasks.test {
    systemProperty("license.provider", "lemonsqueezy")
    systemProperty("ls.store.id", System.getenv("ATL_STORE_ID") ?: "")
    systemProperty("license.key", System.getenv("ATL_LICENSE_KEY") ?: "")
    systemProperty("license.user.email", System.getenv("ATL_LICENSE_EMAIL") ?: "")
}
```

**In CI**, put the key in your secret store and expose it as `ATL_LICENSE_KEY`. It is not a
password — it identifies your subscription, not an account — but treat it like any other secret.

**Checking it worked.** On a licensed run the log contains:

```
LICENSE GRANTED: LICENSE_VALID provider=LEMONSQUEEZY
```

If licensing is misconfigured the build fails with `SecurityException: LICENSE DENIED: <reason>`,
and the message lists the flags. Common reasons:

| Reason | What happened |
|---|---|
| `LICENSE_REQUIRED` | No key supplied, on a commercial email domain |
| `LICENSE_NOT_FOUND` | Key does not exist — check for a copy-paste truncation |
| `LICENSE_INVALID` | Key is real but not for this product, store, or email domain |
| `LICENSE_EXPIRED` | Subscription lapsed — see below |
| `NETWORK_ERROR` | Could not reach `api.lemonsqueezy.com` |

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

`LicenseGuard` resolves these system properties. Everything except `license.provider` is
LemonSqueezy-specific and ignored on the Keygen path.

| Property | Meaning | Default |
|---|---|---|
| `license.provider` | `keygen` or `lemonsqueezy` | `keygen` |
| `ls.store.id` | Numeric store id; **required** for LemonSqueezy | — |
| `ls.product.id` | Optional narrower product scope | unset |
| `ls.email.binding` | `domain` or `exact` | `domain` |
| `ls.api.base.uri` | Override the API host; for tests | LemonSqueezy |
| `license.key` | The customer's key | — |
| `license.user.email` | Address the run is attributed to | `""` |
| `license.mock.mode` | Bypasses the gate entirely | `false` |

`license.provider`, `ls.store.id`, `ls.product.id`, `ls.email.binding` and `license.key` are all
part of `LicenseGuard`'s cache fingerprint, so changing any of them within a JVM recomputes the
decision instead of reusing a cached grant.

See [CONFIGURATION.md](CONFIGURATION.md) for the full `@AsyncTest` surface.
