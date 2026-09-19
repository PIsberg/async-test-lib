# Part 2 — what to send the customer

Part of the [Licensing runbook](../LICENSING.md).

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

`NETWORK_ERROR` no longer fails a licensed build by default: since 1.9.1 an unreachable validator
is treated as an outage and the run proceeds with a warning (grace mode). A validator that answers
and rejects your key still fails the build. If your build agents can never reach the internet, ask
us for an offline license file instead; the full semantics are in
[Part 3](offline.md#part-3--offline-licensing-outages-and-air-gapped-ci).

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
