package se.deversity.asynctest.example.service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * Writes report rows to a sink, slowly, from a background stage.
 *
 * <p>The rows it has written are the side effects that survive a cancellation. Cancelling the
 * {@link java.util.concurrent.CompletableFuture} that renders the report does not reach in here,
 * so unless the export asks whether it should stop, it will not.
 */
public final class ReportExporter {

    private final List<String> written = new CopyOnWriteArrayList<>();

    /** The buggy shape: nothing here can be stopped from outside. */
    public void exportAll(int rows) {
        for (int i = 0; i < rows; i++) {
            written.add("row-" + i);
        }
    }

    /**
     * The fixed shape: the export asks, at a point where abandoning the work is safe, whether
     * anyone still wants it.
     *
     * @param rows      how many rows to write
     * @param cancelled polled between rows; when it answers true the export stops
     */
    public void exportCooperatively(int rows, BooleanSupplier cancelled) {
        for (int i = 0; i < rows; i++) {
            if (cancelled.getAsBoolean()) {
                return;
            }
            written.add("row-" + i);
        }
    }

    /** {@return the rows actually written, in order} */
    public List<String> written() {
        return List.copyOf(written);
    }
}
