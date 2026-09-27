package se.deversity.asynctest.diagnostics;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Text that several detector reports render the same way. */
final class ReportSections {

    /** The last number {@link #unnamed(String)} handed out, shared by every detector in the JVM. */
    private static final AtomicLong UNNAMED = new AtomicLong();

    private ReportSections() {
    }

    /**
     * Labels an object the test gave no name: {@code kind@n}, as in {@code queue@3}, with an
     * {@code n} no earlier call returned. Call it once per object, where its state is created, and
     * keep the result, so every line about the object prints the same label.
     *
     * <p>The fallback was the kind and the object's identity hash, which two live objects share
     * often enough to matter; a report keyed by that label then printed two objects as one line
     * (#854). A number handed out once cannot collide. The shape stays {@code kind@digits}, so a
     * baseline fingerprint, which reads {@code @} and digits as {@code @#}, still matches a
     * finding recorded under the old label.
     *
     * @param kind what the report calls such an object, such as {@code "queue"} or a simple class
     *             name; printed as given
     * @return the label, never returned before in this JVM
     */
    static String unnamed(String kind) {
        return kind + "@" + UNNAMED.incrementAndGet();
    }

    /**
     * Names a thread for a report: its name and id, or {@code #id} alone when it has no name,
     * which is every virtual thread created without one. Named by name only, those threads all
     * printed as "" and collapsed into one entry (#766, #790); the id also keeps two threads that
     * share a name apart.
     *
     * @param thread the thread to name
     * @return the label the report prints for it
     */
    static String threadLabel(Thread thread) {
        String name = thread.getName();
        return name.isEmpty() ? "#" + thread.threadId() : name + " (id=" + thread.threadId() + ")";
    }

    /**
     * Appends a titled bullet list, preceded by a blank line; appends nothing when {@code items}
     * is empty.
     */
    static void appendSection(StringBuilder sb, String title, List<String> items) {
        if (items.isEmpty()) return;
        sb.append("\n  ").append(title).append(":\n");
        for (String item : items) {
            sb.append("    - ").append(item).append("\n");
        }
    }
}
