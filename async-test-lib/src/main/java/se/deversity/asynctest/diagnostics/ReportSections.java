package se.deversity.asynctest.diagnostics;

import java.util.List;

/** Text blocks that several detector reports render the same way. */
final class ReportSections {

    private ReportSections() {
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
