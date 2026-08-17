package se.deversity.asynctest.example.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Writes the parts of an order - the row, the audit entry, the search index - as independent
 * futures, then reads them back.
 *
 * <p>The interesting moment is the one between "all the writes were started" and "all the writes
 * finished". {@code allOf} marks that boundary but does not enforce it: whether the code actually
 * waits depends entirely on what it does with the future {@code allOf} returns.
 */
public final class OrderWriter {

    private final List<String> committed = new CopyOnWriteArrayList<>();
    private final List<String> started   = new CopyOnWriteArrayList<>();

    /** One part of the order, completed by the caller when the write lands. */
    public CompletableFuture<String> beginWrite(String part) {
        started.add(part);
        return new CompletableFuture<>();
    }

    /** {@return the parts whose write was started, committed or not} */
    public List<String> started() {
        return List.copyOf(started);
    }

    /** Marks a part as durably written. */
    public void commit(String part) {
        committed.add(part);
    }

    /** {@return the parts written so far} */
    public List<String> committed() {
        return List.copyOf(committed);
    }

    /** What a reader sees if it looks before the group has finished. */
    public boolean isFullyWritten(int expectedParts) {
        return committed.size() == expectedParts;
    }
}
