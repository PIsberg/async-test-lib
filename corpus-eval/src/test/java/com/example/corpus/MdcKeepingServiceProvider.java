package com.example.corpus;

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.helpers.BasicMDCAdapter;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.NOPLoggerFactory;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

/**
 * An SLF4J backend that logs nothing and keeps the MDC.
 *
 * <p>HikariCP logs through SLF4J and warns on every pool operation without a backend, and the woven
 * {@code MDC_CONTEXT_LEAK} feed reads {@code MDC.getCopyOfContextMap()}. {@code slf4j-nop} answers
 * the first and keeps nothing, so a leaked key could not be shown; a real backend would be a dependency
 * for one row. Everything here ships in {@code slf4j-api}, so this adds none.
 */
public final class MdcKeepingServiceProvider implements SLF4JServiceProvider {

    private final ILoggerFactory loggers = new NOPLoggerFactory();
    private final IMarkerFactory markers = new BasicMarkerFactory();
    private final MDCAdapter mdc = new BasicMDCAdapter();

    @Override
    public ILoggerFactory getLoggerFactory() {
        return loggers;
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return markers;
    }

    @Override
    public MDCAdapter getMDCAdapter() {
        return mdc;
    }

    @Override
    public String getRequestedApiVersion() {
        return "2.0.99";
    }

    @Override
    public void initialize() {
        // Built in the field initializers: nothing here needs the framework to be up first.
    }
}
