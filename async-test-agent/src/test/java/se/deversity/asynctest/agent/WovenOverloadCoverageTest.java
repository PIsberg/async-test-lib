package se.deversity.asynctest.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every overload of a woven method is either woven too, or excused by name.
 *
 * <p><strong>The failure this exists for.</strong> The weaver matches an exact descriptor. For
 * most of its life the shared-instance table listed {@code StringBuilder.append(String)} and
 * {@code append(int)} and nothing else, so a shared builder appended to with a {@code char} went
 * completely unobserved - and the silence was indistinguishable from the silence of correct code.
 * Nothing failed. No log line said a call site had been skipped. {@code STRING_BUILDER} simply
 * never spoke, and the only reason anyone found out is that a corpus row written to fire came
 * back silent (#434).
 *
 * <p>That is the shape of gap this gate closes. Not "is the weaver correct", which the end-to-end
 * weaving tests answer, but "does the table cover the API it claims to cover" - which no runtime
 * test can answer, because an unwoven overload produces exactly the output a woven one produces
 * when there is nothing to report.
 *
 * <p><strong>Why an excuse list rather than a rule.</strong> Weaving every overload is not the
 * goal and would not be free: each costs a hook method, a descriptor match at class-load time,
 * and a branch on a hot JDK call. The judgement about which ones carry real usage is a judgement,
 * so it is written down per method with a reason and reviewed in a diff, exactly as
 * {@code DetectorCoverage} handles detectors the corpus does not pair.
 */
class WovenOverloadCoverageTest {

    /**
     * Overloads that exist, are not woven, and are not going to be, with the reason.
     *
     * <p>A reason here is a decision. An overload that ought to be woven and simply has not been
     * yet belongs in {@link #KNOWN_GAP} instead, where it has to name an issue.
     */
    private static final Map<String, String> NOT_WOVEN = new LinkedHashMap<>();

    /**
     * Overloads that should be woven and are not yet, each naming the issue tracking it.
     *
     * <p>Separate from {@link #NOT_WOVEN} because the two say opposite things and a single list
     * would let one pass for the other. A decision needs no follow-up; a gap does, and an entry
     * that cannot name an issue is a to-do wearing a decision's clothes.
     *
     * <p>Currently empty, which is the state to keep it in.
     */
    private static final Map<String, String> KNOWN_GAP = new LinkedHashMap<>();

    static {
        decided("java.lang.StringBuilder#append([C)",
                "appending a raw char[] to a builder shared across threads is rare enough that a "
                        + "hook and a load-time descriptor match cost more than the coverage buys");
        decided("java.lang.StringBuilder#append([C, int, int)",
                "the ranged form of the overload above");
        decided("java.lang.StringBuilder#append(float)",
                "float formatting through a builder is uncommon, and append(double) covers the "
                        + "shape a shared builder is actually used in");
        decided("java.lang.StringBuilder#append(java.lang.StringBuffer)",
                "StringBuffer is itself synchronized, so a codebase reaching for it has already "
                        + "made the decision this detector exists to prompt");
        decided("java.lang.StringBuilder#append(java.lang.CharSequence, int, int)",
                "the ranged form; append(CharSequence) covers the whole-sequence case a shared "
                        + "builder is used for");
        decided("java.text.NumberFormat#format(java.lang.Object)",
                "declared final on java.text.Format, not on NumberFormat, so a call site's owner "
                        + "is the parent and an entry here would match a different type than it "
                        + "names");
        decided("java.text.SimpleDateFormat#format(java.lang.Object)",
                "declared final on java.text.Format; same reason as NumberFormat.format(Object)");
        decided("java.text.NumberFormat#format(double, java.lang.StringBuffer, "
                        + "java.text.FieldPosition)",
                "the Format SPI, called by the JDK's own formatting machinery rather than by "
                        + "application code; the one-argument overload is what a shared format is "
                        + "reached through");
        decided("java.text.NumberFormat#format(long, java.lang.StringBuffer, "
                        + "java.text.FieldPosition)",
                "the Format SPI; same reason as the double form");
        decided("java.text.NumberFormat#format(java.lang.Object, java.lang.StringBuffer, "
                        + "java.text.FieldPosition)",
                "the Format SPI; same reason as the double form");
        decided("java.text.SimpleDateFormat#format(java.util.Date, java.lang.StringBuffer, "
                        + "java.text.FieldPosition)",
                "the Format SPI; format(Date) is what a shared formatter is reached through");
        decided("java.text.SimpleDateFormat#format(java.lang.Object, java.lang.StringBuffer, "
                        + "java.text.FieldPosition)",
                "the Format SPI; same reason as the Date form");

        // KNOWN_GAP is deliberately empty. The three entries it held - Thread.sleep(Duration),
        // Thread.sleep(long, int) and Map.remove(Object, Object) - were closed in #440, and the
        // mechanism is kept rather than deleted with them: the next overload that ought to be
        // woven and is not needs somewhere to live that is visibly not a decision.
    }

    private static void decided(String callSite, String reason) {
        NOT_WOVEN.put(callSite, reason);
    }

    private static void gap(String callSite, String reason) {
        KNOWN_GAP.put(callSite, reason);
    }


    @Test
    @DisplayName("every overload of a woven method is woven or excused with a reason")
    void everyOverloadIsWovenOrExcused() {
        Set<String> woven = CollectionAccessWeaver.wovenCallSites();
        Set<String> unaccounted = new TreeSet<>();

        for (String site : woven) {
            String owner = site.substring(0, site.indexOf('#'));
            String method = site.substring(site.indexOf('#') + 1, site.indexOf('('));
            for (String sibling : overloadsOf(owner, method)) {
                if (!woven.contains(sibling) && !NOT_WOVEN.containsKey(sibling)
                        && !KNOWN_GAP.containsKey(sibling)) {
                    unaccounted.add(sibling);
                }
            }
        }

        assertTrue(unaccounted.isEmpty(),
                "these overloads of an already-woven method are not woven and carry no reason, so "
                        + "a receiver reached through one of them is unobserved and its detector "
                        + "stays silent for a reason no build reports. Weave it, add it to "
                        + "NOT_WOVEN with why not, or to KNOWN_GAP with the issue: "
                        + unaccounted);
    }

    @Test
    @DisplayName("no excuse outlives the overload it excuses")
    void noExcuseOutlivesItsOverload() {
        Set<String> woven = CollectionAccessWeaver.wovenCallSites();
        List<String> stale = new ArrayList<>();

        Set<String> claimed = new LinkedHashSet<>(NOT_WOVEN.keySet());
        claimed.addAll(KNOWN_GAP.keySet());
        for (String site : claimed) {
            if (woven.contains(site)) {
                stale.add(site + " is woven now");
            } else if (!methodExists(site)) {
                stale.add(site + " matches no method on that type");
            }
        }

        assertTrue(stale.isEmpty(),
                "an excuse that no longer describes anything reads as a reviewed decision and is "
                        + "not one. Delete it: " + stale);
    }

    @Test
    @DisplayName("every known gap names the issue tracking it")
    void everyGapNamesItsIssue() {
        List<String> unattributed = new ArrayList<>();
        for (Map.Entry<String, String> gap : KNOWN_GAP.entrySet()) {
            if (!gap.getValue().matches(".*#[0-9]+.*")) {
                unattributed.add(gap.getKey());
            }
        }
        assertTrue(unattributed.isEmpty(),
                "a gap with no issue behind it is a to-do that nobody will come back to, and it "
                        + "reads here exactly like a decision that was made: " + unattributed);
    }

    /** {@return every public overload of {@code method} on {@code owner}, in call-site form} */
    private static Set<String> overloadsOf(String owner, String method) {
        Set<String> overloads = new LinkedHashSet<>();
        for (Method candidate : loadClass(owner).getMethods()) {
            if (!candidate.getName().equals(method)) {
                continue;
            }
            // A static entry and a virtual one are matched differently by the weaver, and the
            // tables never mix the two for one name, so following the entry's own shape is
            // unnecessary here: what matters is that the descriptor exists and is reachable.
            if (candidate.isSynthetic() || !Modifier.isPublic(candidate.getModifiers())) {
                continue;
            }
            overloads.add(callSite(owner, method, candidate.getParameterTypes()));
        }
        return overloads;
    }

    private static boolean methodExists(String callSite) {
        String owner = callSite.substring(0, callSite.indexOf('#'));
        String method = callSite.substring(callSite.indexOf('#') + 1, callSite.indexOf('('));
        return overloadsOf(owner, method).contains(callSite);
    }

    private static String callSite(String owner, String method, Class<?>[] parameters) {
        StringBuilder site = new StringBuilder(owner).append('#').append(method).append('(');
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) {
                site.append(", ");
            }
            site.append(parameters[i].getName());
        }
        return site.append(')').toString();
    }

    private static Class<?> loadClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    name + " is woven but not loadable from the test classpath", e);
        }
    }
}
