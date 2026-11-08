/*
 * Course Generator - asynchronous Internet connectivity checks
 *
 * Network probes must never run on the Swing Event Dispatch Thread: after
 * macOS sleep/wake a stalled HTTP read can otherwise freeze the entire UI.
 */
package course_generator.utils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javax.swing.SwingUtilities;

/**
 * Runs at most one connection check at a time on a daemon worker thread and
 * reports each completed result on Swing's Event Dispatch Thread.
 *
 * A check still running when the next timer tick fires is skipped; no queue of
 * obsolete checks can accumulate during a long network outage or sleep/wake.
 */
public final class InternetConnectivityMonitor implements AutoCloseable {
    private final BooleanSupplier probe;
    private final Consumer<Boolean> onResult;
    private final ExecutorService executor;
    private final AtomicBoolean checking = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public InternetConnectivityMonitor(BooleanSupplier probe, Consumer<Boolean> onResult) {
        if (probe == null || onResult == null)
            throw new IllegalArgumentException("Probe and result callback are required");
        this.probe = probe;
        this.onResult = onResult;
        this.executor = Executors.newSingleThreadExecutor(task -> {
            Thread worker = new Thread(task, "CourseGenerator-InternetCheck");
            worker.setDaemon(true);
            return worker;
        });
    }

    /**
     * Schedule a check without waiting for the network.
     *
     * @return false if a check is already pending/running or this monitor is closed.
     */
    public boolean requestCheck() {
        if (closed.get() || !checking.compareAndSet(false, true))
            return false;

        try {
            executor.execute(() -> {
                boolean reachable = false;
                try {
                    reachable = probe.getAsBoolean();
                } catch (RuntimeException ex) {
                    // Treat unexpected connectivity errors as offline, not as an
                    // exception on Swing's event thread.
                    reachable = false;
                } finally {
                    checking.set(false);
                }
                final boolean result = reachable;
                if (!closed.get()) {
                    SwingUtilities.invokeLater(() -> {
                        if (!closed.get())
                            onResult.accept(result);
                    });
                }
            });
            return true;
        } catch (RejectedExecutionException ex) {
            checking.set(false);
            return false;
        }
    }

    @Override
    public void close() {
        closed.set(true);
        executor.shutdownNow();
    }
}
