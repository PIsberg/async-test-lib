# Licensing async-test-lib to a customer

How to issue a commercial licence, what to send the customer, what happens when it expires, and
how renewal works.

Two audiences, kept apart on purpose:

- **[Part 1 — for you](#parts)**: the operator runbook, Parts 1a, 1b and 3.
- **[Part 2 — for the customer](licensing/customer-message.md#part-2--what-to-send-the-customer)**: copy-paste and send.

There are **two ways a licence can be sold**, and they behave differently. Pick the section that
matches how the customer paid:

| Sold through | Keys minted by | Customer sets | Covered by |
|---|---|---|---|
| **Paddle** (deversity.se) | Keygen | `-Dlicense.provider=keygen` | [Part 1a](licensing/paddle-keygen.md#part-1a--paddle--keygen) |
| **Lemon Squeezy** | Lemon Squeezy | `-Dlicense.provider=lemonsqueezy` | [Part 1b](licensing/lemon-squeezy.md#part-1b--lemon-squeezy) |

Ids, tokens and API keys are **not** in this repository. They live in
`~/.config/deversity/paddle.env`, `keygen.env` and `lemonsqueezy.env`. Placeholders in the parts in
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
What the code does do is remind. Every grant that did not validate a commercial key (mock mode, CI
auto-mock, free-mail address) prints a three-line notice to stderr once per JVM, naming the
licence, <https://deversity.se/pricing.html> and peter.isberg@deversity.se. It has no off switch
other than a validated key, so it is the one place a company running unlicensed keeps meeting the
terms in its own build log. `LicenseGuard.NONCOMMERCIAL_NOTICE` is the text; change the address or
the URL there and in the two tests that pin it.

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

## Parts

Each part lives in its own file under [`licensing/`](licensing/).

| Document | What it covers |
|----------|----------------|
| [paddle-keygen.md](licensing/paddle-keygen.md) | Operator runbook for a sale through Paddle, with the key minted by Keygen |
| [lemon-squeezy.md](licensing/lemon-squeezy.md) | Operator runbook for a sale through Lemon Squeezy, including a purchase made with the wrong address |
| [customer-message.md](licensing/customer-message.md) | The copy-paste message for the customer: flags, expiry and renewal |
| [offline.md](licensing/offline.md) | What happens during an outage, issuing an offline license file, and what is wired up |
