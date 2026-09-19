# Part 3 — Offline licensing, outages and air-gapped CI

Part of the [Licensing runbook](../LICENSING.md).

Since 1.9.1 the gate separates "the provider said no" from "the provider could not be asked". The
first always fails the build. The second is an availability problem, and enterprise CI is where it
actually happens: egress-blocked runners, proxies, provider outages.

Both flows are drawn as sequence diagrams in
[architecture/diagrams.md](../architecture/diagrams.md): one for online validation, caching and
outage grace, one for offline files end to end (issuance through validation).

## What happens when, exactly

| Situation | Outcome |
|---|---|
| Validator answered: key not found / expired / suspended / wrong scope | **Build fails.** Always, in every mode. |
| Validator host unreachable: outage, DNS blackhole, egress-blocked runner | Build proceeds; one `LICENSE: validator unavailable` WARN per JVM. |
| Validator reachable but erroring (401/429/5xx), no successful validation of this configuration on record | **Build fails.** An erroring host could have rejected the credentials, so grace does not apply. |
| Validator erroring, but this configuration validated successfully before on this machine | Build proceeds with the WARN: for this customer it is an outage, not a rejection. |
| `-Dlicense.network.mode=strict` | Any validation failure fails the build (the pre-1.9.1 behaviour). |
| Offline file valid (`-Dlicense.file`) | Build proceeds. No network is attempted at all. |
| Offline file missing, tampered, expired, wrong product or wrong email scope | **Build fails** with a named `OFFLINE_*` reason. A bad file never falls back to online validation or CI auto-mock. |

Successful online validations are also recorded on disk (a SHA-256 hash of the configuration and
the epoch, never the key itself) and reused for `license.cache.ttl.hours` (default 24). With
`forkEvery = 1` style suites this is the difference between one licensing API call per day and one
per test class. The record doubles as the grace evidence in the table above.

The security reasoning, in one paragraph: the validators map both transport failures and provider
error statuses to the single reason `NETWORK_ERROR`, so granting on that reason unconditionally
would let a fabricated key pass wherever the provider answers with an error. Grace therefore
requires either a connection-level failure (probed directly) or a recorded prior success. A
customer who deliberately blackholes the provider host can run unlicensed, and that is accepted:
`-Dlicense.mock.mode=true` is already printed in the denial message, and enforcement is legal, not
technical (see "Read this before you sell anything").

## Issuing an offline license file (operator)

The signing keypair lives in `~/.config/deversity/offline-license-signing/` (generated once with
the `keygen` mode; the tool refuses to overwrite an existing private key, because rotating it
invalidates every file already issued against released library versions). The library embeds the
matching public key in `OfflineLicense.java`.

```bash
java tools/IssueOfflineLicense.java issue \
  --key ~/.config/deversity/offline-license-signing/private.pem \
  --licensee "Acme Corp AB" \
  --email licence@acme-corp.com \
  --binding domain \
  --expires 2027-08-11 \
  --plan 50-199 \
  --out acme-corp.atl-license
```

`--binding domain` (the default) covers everyone on the licensed address's domain, matching the
Lemon Squeezy semantics; `exact` narrows to the one address; `none` skips the email check for
negotiated site licences. Verify before sending, exactly as the customer's build will read it:

```bash
java tools/IssueOfflineLicense.java verify \
  --pub ~/.config/deversity/offline-license-signing/public.pem \
  --file acme-corp.atl-license --email adeveloper@acme-corp.com
```

Log it in `customers.tsv` like any other licence, with `offline-file` in the channel column.
Renewal is a new file with a later `--expires`; nothing else in the customer's configuration
changes. Price offline files like the matching online tier; they remove the provider dependency,
not the licence.

## What to send the customer (offline file)

**Your async-test-lib offline licence**

Save the attached `.atl-license` file somewhere your builds can read (checking it into your build
repo is fine; it only works for your email domain) and add two flags to your test runs:

```
-Dlicense.file=/path/to/acme-corp.atl-license
-Dlicense.user.email=<any address on your licensed domain>
```

No network access is needed or attempted: the file is signature-verified inside the JVM. If the
file is rejected the build fails with a `LICENSE DENIED: OFFLINE_...` reason naming what is wrong
(truncated in transit, expired, wrong domain). The file expires on the date in our email; renewal
is a replacement file and nothing else changes.


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
| `license.file` | Path to an Ed25519-signed offline license file. When set, it is the entire decision: no provider coordinates, no network, and a bad file fails closed rather than falling back | unset |
| `license.network.mode` | `grace` (outages proceed with a WARN; see Part 3 for the exact conditions) or `strict` (any validation failure fails the build) | `grace` |
| `license.cache.ttl.hours` | How long a recorded successful validation suppresses revalidation. `0` records but never skips; negative disables the cache including the grace record | `24` |
| `license.cache.dir` | Where validation records live | `~/.asynctest` |
| `keygen.base.uri` | Override the Keygen API host; for tests, matching `ls.api.base.uri` | Keygen |
| `license.mock.mode` | Bypasses the gate entirely | `false` |

**Mock mode auto-activates in CI when no credentials are present** (`GITHUB_ACTIONS` or `CI` set,
and no `keygen.api.key` / no LemonSqueezy store+key). That is deliberate — it keeps our own CI and
contributors unblocked — but it also means a test that asserts a *real* denial cannot be written
in this repo: it would pass by mocking rather than by exercising the gate. Regression coverage for
the validators lives in `common-license-lib` instead.

The operator machine closes that gap since 1.9.1. `RealKeygenLicenseE2eTest` and
`RealOfflineLicenseE2eTest` (both `@E2E`) run the standing internal Deversity AB licence -
issued 2026-08-11, licensed address `peter.isberg@deversity.se`, renewal due 2027-08-11 -
against the live Keygen account and the real offline file. They pin `license.network.mode=strict`
and `license.cache.ttl.hours=-1` so neither outage grace nor a cached validation can fake the
grant, and each carries the denial direction (a same-domain decoy for Keygen's exact binding; a
foreign domain and a tampered copy for the offline file), so a green run proves enforcement
rather than the absence of errors. They skip cleanly anywhere the credentials are absent; to run
them:

```bash
set -a; . ~/.config/deversity/e2e-license.env; set +a
mvn -pl async-test-lib test -Dtest='RealKeygenLicenseE2eTest,RealOfflineLicenseE2eTest' \
  -Dsurefire.failIfNoSpecifiedTests=false -P e2e
```

When the licence or the file expires, both tests start failing with the corresponding expiry
reason - that is the renewal reminder working, not a defect.

`license.provider`, `ls.store.id`, `ls.product.id`, `ls.email.binding` and `license.key` are all
part of `LicenseGuard`'s cache fingerprint, so changing any of them within a JVM recomputes the
decision instead of reusing a cached grant.

See [CONFIGURATION.md](../CONFIGURATION.md) for the full `@AsyncTest` surface.
