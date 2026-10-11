package se.deversity.asynctest.example.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs a command-line tool and returns what it printed.
 *
 * <p>{@link #runWaitingFirst} is the version that ships and passes its tests: it waits for the
 * tool, then reads the output. Any tool that prints more than the pipe holds (a few kilobytes on
 * Windows, 64 KiB on Linux) blocks writing, waits for a reader that only starts once it exits,
 * and the call never returns. {@link #run} drains the output while it waits.
 */
public final class ToolRunner {

    /**
     * The defect: waits for the tool, then reads what it printed.
     *
     * @param command the tool and its arguments
     * @return stdout and stderr, merged
     * @throws IOException          if the tool cannot be started or read
     * @throws InterruptedException if interrupted while waiting
     */
    public String runWaitingFirst(List<String> command) throws IOException, InterruptedException {
        Process tool = new ProcessBuilder(command).redirectErrorStream(true).start();
        tool.waitFor();
        return new String(tool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * The fix: drains the merged output on another thread while waiting.
     *
     * @param command the tool and its arguments
     * @return stdout and stderr, merged
     * @throws IOException          if the tool cannot be started
     * @throws InterruptedException if interrupted while waiting
     */
    public String run(List<String> command) throws IOException, InterruptedException {
        Process tool = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (ExecutorService drain = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> output = drain.submit(() ->
                    new String(tool.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            tool.waitFor();
            return output.get();
        } catch (ExecutionException e) {
            throw new IOException("reading the tool's output failed", e.getCause());
        }
    }
}
