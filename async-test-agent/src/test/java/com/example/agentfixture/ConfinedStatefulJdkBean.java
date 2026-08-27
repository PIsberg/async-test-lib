package com.example.agentfixture;

import java.text.DecimalFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * The correct twin of {@link SharedStatefulJdkBean}: one instance per call, never shared.
 *
 * <p>Building the object inside the method is the fix most codebases apply, and it must stay
 * silent. The agent substitutes exactly the same call sites here, so the difference the detectors
 * have to see is not which instruction ran but how many threads touched one instance. A finding
 * here would put a false positive on every correctly written use in a woven codebase, which is a
 * far larger population than the buggy one.
 */
public class ConfinedStatefulJdkBean {

    /** {@return the year, from a calendar this call owns} */
    public int year() {
        return Calendar.getInstance(Locale.ROOT).get(Calendar.YEAR);
    }

    /** {@return text built by a builder this call owns} */
    public String append() {
        return new StringBuilder().append("x").append(1).toString();
    }

    /** {@return a number formatted by a format this call owns} */
    public String money() {
        return new DecimalFormat("#.##").format(12.345d);
    }
}
