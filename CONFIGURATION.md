# Configuration Guide: License Gating

The `async-test-lib` integrates with `common-license-lib` to provide commercial license gating via **Keygen** and **LemonSqueezy**.

## 1. Quick Start (Development/Mock Mode)
To run tests without needing real API keys (bypassing network calls):
- **Annotation**: `@AsyncTest(licenseMockMode = true)`
- **CLI**: `mvn test -Dlicense.mock.mode=true`

## 2. Production Configuration

### Field Mapping
You can provide credentials via the `@AsyncTest` annotation or via System Properties. System Properties take precedence and are safer for sensitive keys.

| Integration Field | Annotation Attribute | System Property |
| :--- | :--- | :--- |
| **Keygen Account ID** | `keygenAccountId` | `keygen.account.id` |
| **Keygen API Key** | `keygenApiKey` | `keygen.api.key` |
| **Keygen Product ID** | `keygenProductId` | `keygen.product.id` |
| **User License Key** | `licenseKey` | `license.key` |
| **LemonSqueezy Store** | `lemonSqueezyStore` | `ls.store.subdomain` |

### Example Usage

```java
@AsyncTest(
    threads = 10,
    keygenAccountId = "my-keygen-acc",
    keygenProductId = "prod_12345",
    licenseKey = "ABC-DEF-GHI"
)
void myEnterpriseTest() {
    // Logic will only run if license is valid
}
```

## 3. Enforcement
The library uses a **Fail-Closed** policy by default. If the license check fails (Invalid key, expired, or network error), the runner will throw a `SecurityException` and the test will fail.

To allow testing during network outages, you can set `-Dlicense.allow.on.network.error=true` (if supported by your `LicenseGate` version).

## 4. Account Setup & License Acquisition

### Zero-Config CI (GitHub Actions)
The library includes **Zero-Config CI support**. If it detects a CI environment (e.g., `GITHUB_ACTIONS=true` or `CI=true`) and no license credentials are provided, it will **automatically enable mock mode**. This allows your public builds and pull requests to pass without needing to manage secrets or license keys.

### Manual Setup
For detailed information on:
- **Where to find your API credentials** in Keygen and Lemon Squeezy.
- **How end-users obtain a license** via the checkout flow.
- **Detailed field mapping** and system properties.

See the central **[License Gating Guide](https://github.com/PIsberg/common-license-lib/blob/main/docs/LICENSE_GUIDE.md)** (or `../common-license-lib/docs/LICENSE_GUIDE.md` in local development).
