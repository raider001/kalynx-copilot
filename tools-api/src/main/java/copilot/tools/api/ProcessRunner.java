package copilot.tools.api;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Utility for running shell commands and capturing their output.
 * Used by build-tool modules (maven-tools, gradle-tools) to execute
 * build commands in the project directory.
 */
public class ProcessRunner {

    private ProcessRunner() {}

    /** Immutable result of a process execution. */
    public record Result(
            int     exitCode,
            String  stdout,
            String  stderr,
            boolean timedOut
    ) {
        public boolean success()  { return !timedOut && exitCode == 0; }
        public String  combined() { return stdout + (stderr.isBlank() ? "" : "\n" + stderr); }
    }

    /**
     * Runs {@code command} in {@code workingDir}, waits up to {@code timeoutSeconds},
     * and returns the captured output.
     *
     * @param command        command + arguments array
     * @param workingDir     working directory for the process
     * @param timeoutSeconds maximum time to wait before killing the process
     */
    public static Result run(String[] command, File workingDir, int timeoutSeconds) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir);
        pb.redirectErrorStream(false); // capture stdout and stderr separately

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return new Result(-1, "", "Failed to start process: " + e.getMessage(), false);
        }

        // Drain stdout and stderr on separate threads to prevent blocking
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        Thread outThread = drain(process.getInputStream(), stdout);
        Thread errThread = drain(process.getErrorStream(), stderr);

        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Result(-1, stdout.toString(), stderr.toString(), true);
        }

        if (!finished) {
            process.destroyForcibly();
            // Still collect whatever output arrived before the timeout
            joinQuietly(outThread);
            joinQuietly(errThread);
            return new Result(-1, stdout.toString(), stderr.toString(), true);
        }

        joinQuietly(outThread);
        joinQuietly(errThread);

        return new Result(process.exitValue(), stdout.toString(), stderr.toString(), false);
    }

    private static Thread drain(InputStream is, StringBuilder target) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    target.append(line).append('\n');
                }
            } catch (IOException ignored) {}
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void joinQuietly(Thread t) {
        try { t.join(5_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
