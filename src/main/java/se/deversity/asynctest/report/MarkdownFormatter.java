package se.deversity.asynctest.report;

import se.deversity.vibetags.annotations.AIPublicAPI;

import java.util.List;

/**
 * Renders violations as Markdown — useful for PR comments, README diagnostics,
 * and human-facing reports in CI logs.
 *
 * @since 1.6.0
 */
@AIPublicAPI
public final class MarkdownFormatter implements Formatter {

    @Override
    public String format(List<Violation> violations) {
        if (violations.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("## AsyncTest Violations\n\n");
        for (Violation v : violations) {
            sb.append("### ").append(v.detector())
              .append(" [").append(v.severity()).append("]\n");
            sb.append(v.message()).append("\n\n");
            if (!v.sites().isEmpty()) {
                sb.append("**Access sites:**\n");
                for (var s : v.sites()) {
                    sb.append("- `").append(s.render()).append("`\n");
                }
                sb.append('\n');
            }
            if (!v.attributes().isEmpty()) {
                sb.append("**Details:**\n");
                v.attributes().forEach((k, val) ->
                        sb.append("- ").append(k).append(": `").append(val).append("`\n"));
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}
