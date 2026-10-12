package com.example.agentfixture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Starts a child process and waits for it the way application code does, recording nothing.
 *
 * <p>The child is the running JVM's own {@code java -version}: on every machine the suite runs on,
 * quick, and small enough in output that every method here returns. {@link #waitThenRead} waits
 * before anyone reads the pipe, which hangs a verbose child; {@link #drainWhileWaiting} reads on a
 * thread of its own, {@link #readThenWait} reads first and {@link #discardThenWait} leaves no pipe.
 * Woven with {@code collections=true}, {@code waitFor} and the pipe getters reach the hooks.
 */
public class ProcessUsingBean {

    private static ProcessBuilder javaVersion() {
        String java = ProcessHandle.current().info().command().orElse("java");
        return new ProcessBuilder(java, "-version");
    }

    /**
     * Waits for the child, then reads its merged output: the defect.
     *
     * @return the child's output
     * @throws IOException          if the child cannot be started or read
     * @throws InterruptedException if interrupted while waiting
     */
    public String waitThenRead() throws IOException, InterruptedException {
        Process child = javaVersion().redirectErrorStream(true).start();
        child.waitFor();
        return new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * Starts a thread that drains the merged output, then waits for the child.
     *
     * @return the child's output
     * @throws IOException          if the child cannot be started
     * @throws InterruptedException if interrupted while waiting
     */
    public String drainWhileWaiting() throws IOException, InterruptedException {
        Process child = javaVersion().redirectErrorStream(true).start();
        AtomicReference<String> out = new AtomicReference<>();
        Thread drain = new Thread(() -> {
            try {
                out.set(new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        drain.start();
        child.waitFor();
        drain.join();
        return out.get();
    }

    /**
     * Reads the merged output to its end, then waits for the child.
     *
     * @return the child's output
     * @throws IOException          if the child cannot be started or read
     * @throws InterruptedException if interrupted while waiting
     */
    public String readThenWait() throws IOException, InterruptedException {
        Process child = javaVersion().redirectErrorStream(true).start();
        String out = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        child.waitFor();
        return out;
    }

    /**
     * Takes the merged output, waits for the child, and only then reads it: the defect, though the
     * take came first (#998).
     *
     * @return the child's output
     * @throws IOException          if the child cannot be started or read
     * @throws InterruptedException if interrupted while waiting
     */
    public String takeThenWaitThenRead() throws IOException, InterruptedException {
        Process child = javaVersion().redirectErrorStream(true).start();
        java.io.InputStream out = child.getInputStream();
        child.waitFor();
        return new String(out.readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * Takes the merged output, hands the stream to a thread that drains it, then waits (#998).
     *
     * @return the child's output
     * @throws IOException          if the child cannot be started
     * @throws InterruptedException if interrupted while waiting
     */
    public String takeThenHandOffThenWait() throws IOException, InterruptedException {
        Process child = javaVersion().redirectErrorStream(true).start();
        java.io.InputStream stream = child.getInputStream();
        AtomicReference<String> out = new AtomicReference<>();
        Thread drain = new Thread(() -> {
            try {
                out.set(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        drain.start();
        child.waitFor();
        drain.join();
        return out.get();
    }

    /**
     * Discards both outputs, then waits for the child.
     *
     * @return the child's exit value
     * @throws IOException          if the child cannot be started
     * @throws InterruptedException if interrupted while waiting
     */
    public int discardThenWait() throws IOException, InterruptedException {
        return javaVersion()
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor();
    }
}
