import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Operator tool for async-test-lib offline license files. Not part of any published artifact:
 * it lives outside the Maven modules and runs directly with {@code java tools/IssueOfflineLicense.java}.
 *
 * <p>An offline license file is one line:
 *
 * <pre>ATL1.&lt;base64url(payload)&gt;.&lt;base64url(Ed25519 signature over the payload bytes)&gt;</pre>
 *
 * <p>The payload is UTF-8 {@code key=value} lines (java.util.Properties syntax) with the fields
 * {@code product}, {@code licensee}, {@code email}, {@code binding} (domain|exact|none),
 * {@code expires} (ISO date), {@code issued}, {@code plan}. The library verifies the signature
 * against the public key embedded in {@code se.deversity.asynctest.runner.OfflineLicense} and
 * enforces product, expiry and email binding. See docs/LICENSING.md Part 3.
 *
 * <p>Modes:
 * <pre>
 * java tools/IssueOfflineLicense.java keygen &lt;dir&gt;
 *     Generate the Ed25519 signing keypair into &lt;dir&gt;/private.pem and &lt;dir&gt;/public.pem.
 *     Refuses to overwrite an existing private.pem. Prints the public key to embed in the
 *     library. Run once; the private key must never leave the operator machine.
 *
 * java tools/IssueOfflineLicense.java issue --key &lt;private.pem&gt; --licensee "Acme Corp AB" \
 *     --email licence@acme-corp.com [--binding domain|exact|none] --expires 2027-08-11 \
 *     [--plan 50-199] --out acme.atl-license
 *     Sign and write a license file.
 *
 * java tools/IssueOfflineLicense.java verify --pub &lt;public.pem&gt; --file &lt;file&gt; [--email &lt;addr&gt;]
 *     Re-run the library's checks against a file before sending it to a customer.
 * </pre>
 */
public final class IssueOfflineLicense {

    private static final String PREFIX = "ATL1";
    private static final String PRODUCT = "async-test-lib";

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }
        switch (args[0]) {
            case "keygen" -> keygen(args);
            case "issue" -> issue(parseOpts(args));
            case "verify" -> verify(parseOpts(args));
            default -> {
                usage();
                System.exit(2);
            }
        }
    }

    private static void keygen(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: keygen <dir>");
            System.exit(2);
        }
        Path dir = Path.of(args[1]);
        Files.createDirectories(dir);
        Path priv = dir.resolve("private.pem");
        Path pub = dir.resolve("public.pem");
        if (Files.exists(priv)) {
            System.err.println("REFUSING: " + priv + " already exists. Issued licenses verify against"
                + " the key embedded in released library versions; replacing the private key breaks"
                + " every file already issued. Delete it yourself if you really mean to rotate.");
            System.exit(1);
        }
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        writePem(priv, "PRIVATE KEY", kp.getPrivate().getEncoded());
        writePem(pub, "PUBLIC KEY", kp.getPublic().getEncoded());
        System.out.println("Wrote " + priv + " (keep this on the operator machine only)");
        System.out.println("Wrote " + pub);
        System.out.println();
        System.out.println("Public key (base64 SubjectPublicKeyInfo) to embed in OfflineLicense.java:");
        System.out.println(Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()));
    }

    private static void issue(Map<String, String> o) throws Exception {
        String keyPath = require(o, "key");
        String licensee = require(o, "licensee");
        String email = require(o, "email");
        String binding = o.getOrDefault("binding", "domain");
        String expires = require(o, "expires");
        String out = require(o, "out");
        String plan = o.get("plan");

        if (!binding.equals("domain") && !binding.equals("exact") && !binding.equals("none")) {
            fail("--binding must be domain, exact or none, got: " + binding);
        }
        LocalDate exp = LocalDate.parse(expires);
        if (!exp.isAfter(LocalDate.now(ZoneOffset.UTC))) {
            fail("--expires " + expires + " is not in the future");
        }

        StringBuilder payload = new StringBuilder();
        field(payload, "product", PRODUCT);
        field(payload, "licensee", licensee);
        field(payload, "email", email);
        field(payload, "binding", binding);
        field(payload, "issued", LocalDate.now(ZoneOffset.UTC).toString());
        field(payload, "expires", exp.toString());
        if (plan != null) {
            field(payload, "plan", plan);
        }
        byte[] payloadBytes = payload.toString().getBytes(StandardCharsets.UTF_8);

        PrivateKey priv = readPrivate(Path.of(keyPath));
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(priv);
        sig.update(payloadBytes);
        byte[] signature = sig.sign();

        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String file = PREFIX + "." + b64.encodeToString(payloadBytes) + "." + b64.encodeToString(signature);
        Files.writeString(Path.of(out), file + System.lineSeparator(), StandardCharsets.UTF_8);

        System.out.println("Issued " + out);
        System.out.println("  licensee: " + licensee);
        System.out.println("  email:    " + email + " (binding: " + binding + ")");
        System.out.println("  expires:  " + exp);
        System.out.println();
        System.out.println("Verify before sending:");
        System.out.println("  java tools/IssueOfflineLicense.java verify --pub <public.pem> --file " + out
            + " --email " + email);
        System.out.println();
        System.out.println("Log it (same convention as online keys):");
        System.out.println("  echo \"$(date -I)  " + licensee + "  " + email + "  offline-file  expires:"
            + exp + "\" >> ~/.config/deversity/customers.tsv");
    }

    private static void verify(Map<String, String> o) throws Exception {
        String pubPath = require(o, "pub");
        String filePath = require(o, "file");
        String email = o.get("email");

        String content = Files.readString(Path.of(filePath), StandardCharsets.UTF_8).trim();
        String[] parts = content.split("\\.");
        if (parts.length != 3 || !PREFIX.equals(parts[0])) {
            fail("malformed: expected " + PREFIX + ".<payload>.<signature>");
        }
        byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
        byte[] signature = Base64.getUrlDecoder().decode(parts[2]);

        PublicKey pub = readPublic(Path.of(pubPath));
        Signature sig = Signature.getInstance("Ed25519");
        sig.initVerify(pub);
        sig.update(payloadBytes);
        if (!sig.verify(signature)) {
            fail("SIGNATURE INVALID");
        }

        Properties p = new Properties();
        p.load(new StringReader(new String(payloadBytes, StandardCharsets.UTF_8)));
        System.out.println("Signature: VALID");
        for (String k : new String[] {"product", "licensee", "email", "binding", "issued", "expires", "plan"}) {
            if (p.getProperty(k) != null) {
                System.out.println("  " + k + ": " + p.getProperty(k));
            }
        }
        if (!PRODUCT.equals(p.getProperty("product"))) {
            fail("wrong product: " + p.getProperty("product"));
        }
        LocalDate exp = LocalDate.parse(p.getProperty("expires"));
        if (LocalDate.now(ZoneOffset.UTC).isAfter(exp)) {
            fail("EXPIRED on " + exp);
        }
        if (email != null) {
            String binding = p.getProperty("binding", "domain");
            String licensed = p.getProperty("email", "");
            boolean ok = switch (binding) {
                case "none" -> true;
                case "exact" -> licensed.trim().equalsIgnoreCase(email.trim());
                case "domain" -> domainOf(licensed) != null && domainOf(licensed).equalsIgnoreCase(domainOf(email));
                default -> false;
            };
            if (!ok) {
                fail("email " + email + " is not covered (binding=" + binding + ", licensed=" + licensed + ")");
            }
            System.out.println("  covers " + email + ": yes");
        }
        System.out.println("VALID until " + exp);
    }

    private static String domainOf(String email) {
        int at = email == null ? -1 : email.lastIndexOf('@');
        return at <= 0 || at == email.length() - 1 ? null : email.substring(at + 1);
    }

    /** Writes one payload line, escaping backslashes so Properties.load reads the value back verbatim. */
    private static void field(StringBuilder sb, String key, String value) {
        sb.append(key).append('=').append(value.replace("\\", "\\\\")).append('\n');
    }

    private static void writePem(Path path, String label, byte[] der) throws IOException {
        String b64 = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
        Files.writeString(path,
            "-----BEGIN " + label + "-----\n" + b64 + "\n-----END " + label + "-----\n",
            StandardCharsets.UTF_8);
    }

    private static byte[] readPem(Path path, String label) throws IOException {
        String pem = Files.readString(path, StandardCharsets.UTF_8);
        String body = pem.replace("-----BEGIN " + label + "-----", "")
            .replace("-----END " + label + "-----", "")
            .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    private static PrivateKey readPrivate(Path path) throws Exception {
        return KeyFactory.getInstance("Ed25519")
            .generatePrivate(new PKCS8EncodedKeySpec(readPem(path, "PRIVATE KEY")));
    }

    private static PublicKey readPublic(Path path) throws Exception {
        return KeyFactory.getInstance("Ed25519")
            .generatePublic(new X509EncodedKeySpec(readPem(path, "PUBLIC KEY")));
    }

    private static Map<String, String> parseOpts(String[] args) {
        Map<String, String> out = new HashMap<>();
        for (int i = 1; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                fail("expected --option value pairs, got: " + args[i]);
            }
            out.put(args[i].substring(2), args[i + 1]);
        }
        return out;
    }

    private static String require(Map<String, String> opts, String key) {
        String v = opts.get(key);
        if (v == null || v.isBlank()) {
            fail("missing required option --" + key);
        }
        return v;
    }

    private static void fail(String msg) {
        System.err.println(msg);
        System.exit(1);
        throw new IllegalStateException("unreachable");
    }

    private static void usage() {
        System.err.println("usage: java tools/IssueOfflineLicense.java keygen <dir>");
        System.err.println("       java tools/IssueOfflineLicense.java issue --key <private.pem>"
            + " --licensee <name> --email <addr> [--binding domain|exact|none]"
            + " --expires <yyyy-mm-dd> [--plan <tier>] --out <file>");
        System.err.println("       java tools/IssueOfflineLicense.java verify --pub <public.pem>"
            + " --file <file> [--email <addr>]");
    }

    private IssueOfflineLicense() {}
}
