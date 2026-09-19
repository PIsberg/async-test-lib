# Part 1a — Paddle + Keygen

Part of the [Licensing runbook](../LICENSING.md).

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

Because Keygen binds to one exact address (see [Read this before you sell anything](../LICENSING.md#read-this-before-you-sell-anything)), ask the customer which address their builds
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

Use the Keygen block in [Part 2](customer-message.md#part-2--what-to-send-the-customer).

## Step 7 — log it

```bash
echo "$(date -I)  <company>  <licensed-address>  paddle  <transaction-id>  renews:<date>"   >> ~/.config/deversity/customers.tsv
```

Renewal is Paddle's job — the subscription rebills yearly. The Keygen licence does **not** extend
itself when Paddle rebills: nothing connects the two today. Watch for the renewal notification and
extend the licence's expiry in Keygen, or the customer's build starts failing a year after they
bought.
