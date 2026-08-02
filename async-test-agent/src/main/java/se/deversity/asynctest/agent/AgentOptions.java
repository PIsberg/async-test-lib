package se.deversity.asynctest.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

import se.deversity.vibetags.annotations.AIContract;

/**
 * Immutable, parsed view of the {@code agentArgs} string passed to
 * {@link AsyncTestAgent#premain(String, java.lang.instrument.Instrumentation)}.
 *
 * <h2>Grammar</h2>
 * {@code agentArgs} is a list of {@code key=value} entries. Entries are separated by
 * either a comma ({@code ,}) or a semicolon ({@code ;}); a value that continues a key
 * across separators (a bare token with no {@code =}) is appended to the most recently
 * named key. This lets a single key carry several values, e.g.
 * {@code includes=com.myapp;com.other}. Recognised keys:
 * <ul>
 *   <li>{@code includes} — one or more fully-qualified package/class name prefixes. When
 *       present, only types whose name starts with one of the prefixes are instrumented
 *       (the positive {@code type(...)} match is narrowed).</li>
 *   <li>{@code excludes} — one or more name prefixes appended to the built-in ignore
 *       matcher, so matching types are never instrumented.</li>
 *   <li>{@code debug} — a boolean flag ({@code debug=true}) that turns on verbose agent
 *       diagnostics: every successfully instrumented type is logged and instrumentation
 *       errors carry a full stack trace. Any value other than {@code true} (case
 *       insensitive) — or the key's absence — leaves diagnostics at the default
 *       errors-only, one-line level.</li>
 * </ul>
 *
 * <h2>Examples</h2>
 * <pre>{@code
 * // Instrument only com.myapp.*, but never com.myapp.dto.*:
 * -javaagent:async-test-agent.jar=includes=com.myapp;excludes=com.myapp.dto
 *
 * // Instrument two roots (semicolon-separated value list):
 * -javaagent:async-test-agent.jar=includes=com.myapp;com.other
 *
 * // Instrument com.myapp.* with verbose transform diagnostics:
 * -javaagent:async-test-agent.jar=includes=com.myapp,debug=true
 * }</pre>
 *
 * <h2>Robustness</h2>
 * Parsing is total: it never throws (a thrown exception in {@code premain} aborts JVM
 * startup). Whitespace around keys and values is trimmed, empty entries are skipped,
 * keys are matched case-insensitively, and unknown keys are ignored. A {@code null} or
 * blank argument yields empty {@code includes} and {@code excludes} lists, which restore
 * the fully backward-compatible {@code any()} instrumentation behavior.
 *
 * <p>Package-private so it can be unit-tested without a live
 * {@link java.lang.instrument.Instrumentation} handle.
 *
 * @since 1.7.0
 */
@AIContract(reason = "The class is package-private but the agentArgs grammar it parses is public surface: users type it on the -javaagent: command line. Key names (includes/excludes/debug), the comma-or-semicolon separator, the bare-token continuation that lets one key carry several values, and case-insensitive key matching are all part of that contract — changing any of them breaks existing launch scripts silently. Parsing must stay total: it is called from premain, where a thrown exception aborts JVM startup, so unknown keys are ignored and malformed input degrades to the default instrument-everything behaviour rather than failing.")
final class AgentOptions {

    private final List<String> includes;
    private final List<String> excludes;
    private final boolean debug;

    private AgentOptions(List<String> includes, List<String> excludes, boolean debug) {
        this.includes = List.copyOf(includes);
        this.excludes = List.copyOf(excludes);
        this.debug = debug;
    }

    /**
     * Parses an {@code agentArgs} string into an {@link AgentOptions}.
     *
     * <p>Never throws: malformed input is tolerated (see the class Javadoc). Unknown
     * keys, empty entries, and bare tokens that precede any key are ignored.
     *
     * @param agentArgs the raw agent argument string, or {@code null}
     * @return the parsed options; empty lists when {@code agentArgs} is {@code null} or
     *         carries no recognised values
     */
    static AgentOptions parse(@Nullable String agentArgs) {
        List<String> includes = new ArrayList<>();
        List<String> excludes = new ArrayList<>();
        boolean debug = false;
        if (agentArgs != null) {
            String currentKey = null;
            for (String token : agentArgs.split("[,;]")) {
                String entry = token.trim();
                if (entry.isEmpty()) {
                    continue;
                }
                String value = entry;
                int eq = entry.indexOf('=');
                if (eq >= 0) {
                    currentKey = entry.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                    value = entry.substring(eq + 1).trim();
                }
                if (value.isEmpty()) {
                    continue;
                }
                if ("includes".equals(currentKey)) {
                    includes.add(value);
                } else if ("excludes".equals(currentKey)) {
                    excludes.add(value);
                } else if ("debug".equals(currentKey)) {
                    debug = Boolean.parseBoolean(value);
                }
                // Unknown keys (and bare values before any key) are ignored on purpose.
            }
        }
        return new AgentOptions(includes, excludes, debug);
    }

    /**
     * The include prefixes that narrow the positive type match.
     *
     * @return an immutable list of name prefixes; empty means "instrument everything not
     *         ignored"
     */
    List<String> includes() {
        return includes;
    }

    /**
     * The exclude prefixes appended to the built-in ignore matcher.
     *
     * @return an immutable list of name prefixes; empty means "add no extra exclusions"
     */
    List<String> excludes() {
        return excludes;
    }

    /**
     * Whether verbose agent diagnostics are enabled.
     *
     * <p>When {@code true}, the agent's {@code AgentBuilder.Listener} additionally logs
     * every successfully instrumented type and emits full stack traces for
     * instrumentation errors. When {@code false} (the default), only a single-line
     * message is logged per instrumentation error.
     *
     * @return {@code true} if {@code debug=true} was supplied, {@code false} otherwise
     * @since 1.7.0
     */
    boolean debug() {
        return debug;
    }
}
