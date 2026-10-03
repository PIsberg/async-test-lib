package se.deversity.asynctest.diagnostics;

import java.util.List;

/** Text that several detector reports render the same way. */
final class ReportSections {

    private ReportSections() {
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
