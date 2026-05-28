package se.deversity.asynctest.report;

import se.deversity.vibetags.annotations.AIPublicAPI;

import java.util.List;

/**
 * Strategy for rendering a batch of {@link Violation}s into a single string.
 *
 * <p>Implementations live alongside the framework or are provided by users.
 * Build-in formatters: {@link MarkdownFormatter}, {@link JsonFormatter}.
 *
 * @since 1.6.0
 */
@AIPublicAPI
@FunctionalInterface
public interface Formatter {

    /**
     * Render the violations. Empty lists must produce a non-null result
     * (typically an empty string, or a "no violations" marker — formatter's choice).
     */
    String format(List<Violation> violations);
}
