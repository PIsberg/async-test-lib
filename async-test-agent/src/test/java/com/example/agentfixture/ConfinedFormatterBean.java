package com.example.agentfixture;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The correct twin of {@link SharedFormatterBean}: a formatter per call, never shared.
 *
 * <p>Constructing one per call is the fix most codebases actually apply, and it must stay silent.
 * The agent substitutes exactly the same {@code format} call site here, so the difference the
 * detector has to see is not which instruction ran but how many threads touched one instance. A
 * finding here would mean the substitution is reporting the call rather than the sharing, which
 * would put a false positive on every correctly written formatter in a woven codebase.
 */
public class ConfinedFormatterBean {

    /**
     * {@return the date, formatted through a formatter this call owns}
     *
     * @param date the date to format
     */
    public String render(Date date) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(date);
    }
}
