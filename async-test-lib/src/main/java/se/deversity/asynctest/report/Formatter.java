package se.deversity.asynctest.report;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AIExtensible;
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
@AIContract(reason = "Public formatter SPI. format(List<Violation>) signature must not change — built-in formatters and user-provided lambdas bind to this exact type.")
@AIExtensible(AIExtensible.Strategy.STRATEGY_PATTERN)
@FunctionalInterface
@API(status = Status.STABLE)
public interface Formatter {

    /**
     * Render the violations. Empty lists must produce a non-null result
     * (typically an empty string, or a "no violations" marker — formatter's choice).
     *
     * @param violations the findings to render, possibly empty but never {@code null}
     * @return the rendered report, never {@code null}
     */
    String format(List<Violation> violations);
}
