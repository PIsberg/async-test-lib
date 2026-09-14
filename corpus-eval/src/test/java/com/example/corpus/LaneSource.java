package com.example.corpus;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a test body, and the lane methods it calls, out of a lane's source text.
 *
 * <p>Two gates ask a question only the source can answer, because a detector cannot be asked
 * afterwards which of its methods a body used: {@link SilentRowPremise} whether a silent row
 * addressed its detector at all, and {@link PairEvidence} whether both halves of a pair addressed
 * it the same way. Each carried its own copy of this, and both copies read a statement keyword as
 * a helper call. {@code synchronized (MONITOR)} looked like a call to a method named
 * {@code synchronized}, and the declaration pattern then matched the first
 * synchronized block anywhere in the lane, so a body was credited with another
 * test's detector calls. That held the {@code NOTIFY_WITHOUT_MONITOR} pair back as differing in
 * shape, and it could equally have let a silent row that never reached its detector pass on a
 * neighbour's calls.
 *
 * <p>Brace matching rather than a parser. The lane is one class of ordinary methods with no string
 * literal containing an unbalanced brace, and a parser dependency for this would be a row in
 * {@code docs/DEPENDENCIES.md} rather than a convenience.
 */
final class LaneSource {

    /**
     * Words followed by a parenthesis that are not method names. Every one of them can open a
     * block with a closing parenthesis and an opening brace, which is the shape the declaration pattern looks for.
     */
    private static final Set<String> STATEMENT_KEYWORDS =
            Set.of("if", "for", "while", "switch", "catch", "synchronized", "try");

    private LaneSource() {
    }

    /**
     * {@return {@code testMethod}'s body, plus the bodies of the lane's own methods it calls}
     *
     * <p>One level of indirection is followed and no more, which is what the lane actually uses:
     * {@code recorded_mutableIntKey_neverMutated} reaches its detector through {@code fileKeyOnce}
     * and would read as never reaching it without this. A helper that itself delegates further
     * would escape, and the answer to that is a deeper walk, not a looser one.
     *
     * @param source     the lane's source
     * @param testMethod the row's test method
     */
    static String bodyWithHelpers(String source, String testMethod) {
        String body = bodyOf(source, testMethod);
        StringBuilder reachable = new StringBuilder(body);
        Matcher calls = Pattern.compile("\\b([a-z][A-Za-z0-9_]*)\\s*\\(").matcher(body);
        Set<String> seen = new LinkedHashSet<>();
        while (calls.find()) {
            String name = calls.group(1);
            if (seen.add(name) && !name.equals(testMethod)) {
                reachable.append('\n').append(bodyOf(source, name));
            }
        }
        return reachable.toString();
    }

    /**
     * {@return the source text of {@code methodName}'s body, or empty if the lane has no such method}
     *
     * @param source     the lane's source
     * @param methodName the method to extract
     */
    static String bodyOf(String source, String methodName) {
        if (STATEMENT_KEYWORDS.contains(methodName)) {
            return "";
        }
        Matcher declaration = Pattern.compile(
                "(?m)^\\s*(?:@\\w+\\s+)*(?:private|public|protected|static|final|void|[A-Za-z<>\\[\\],.?\\s]+?)\\b"
                        + Pattern.quote(methodName)
                        // A declaration may carry a throws clause between the parameter list
                        // and the body; without this alternative such a method reads as absent
                        // and its silent row as never reaching the detector.
                        + "\\s*\\([^)]*\\)\\s*(?:throws\\s+[A-Za-z0-9_$.,\\s]+)?\\{").matcher(source);
        if (!declaration.find()) {
            return "";
        }
        int open = source.indexOf('{', declaration.start());
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(open, i + 1);
                }
            }
        }
        return source.substring(open);
    }
}
