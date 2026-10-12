package com.example.agentfixture;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Uses a piped stream pair the two ways the {@code PipedInputStream} javadoc contrasts, recording
 * nothing: from one thread, which it warns may deadlock, and with a thread per end. The writes stay
 * under the pipe's 1024-byte buffer, so neither actually blocks. Woven with
 * {@code collections=true}, the reads and writes reach the pipe hooks.
 */
public class PipedStreamBean {

    /**
     * Writes into a pipe and reads it back on the calling thread.
     *
     * @return the byte read
     * @throws IOException if the pipe fails
     */
    public int bothEndsOnOneThread() throws IOException {
        PipedInputStream in = new PipedInputStream();
        PipedOutputStream out = new PipedOutputStream(in);
        out.write(42);
        out.close();
        return in.read();
    }

    /**
     * Writes into a pipe on a thread of its own and reads it on the calling thread.
     *
     * @return the byte read
     * @throws IOException          if the pipe fails
     * @throws InterruptedException if interrupted while joining the writer
     */
    public int oneThreadPerEnd() throws IOException, InterruptedException {
        PipedInputStream in = new PipedInputStream();
        PipedOutputStream out = new PipedOutputStream(in);
        AtomicReference<IOException> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                out.write(42);
                out.close();
            } catch (IOException e) {
                failure.set(e);
            }
        });
        writer.start();
        int read = in.read();
        writer.join();
        if (failure.get() != null) {
            throw new UncheckedIOException(failure.get());
        }
        return read;
    }
}
