// Mints a Keygen licence for one nominated address and prints it as KEY=<key>.
// Driven by issue-license.sh, which supplies the classpath and the environment.
import se.deversity.common.license.keygen.KeygenIssuer;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

var issuer = new KeygenIssuer(
    HttpClient.newHttpClient(),
    System.getenv("KEYGEN_ACCOUNT_ID"),
    System.getenv("KEYGEN_ADMIN_TOKEN"),
    URI.create("https://api.keygen.sh"),
    Duration.ofSeconds(30));

// issueLicense creates the Keygen user if it does not already exist, then a licence
// owned by them under the annual policy. Re-running for the same address mints a
// SECOND licence rather than returning the first — check before re-running.
System.out.println("KEY=" + issuer.issueLicense(
    System.getenv("KEYGEN_POLICY_ID"),
    System.getenv("ATL_OWNER_EMAIL")));
System.exit(0);
