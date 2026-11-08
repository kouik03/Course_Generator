package course_generator.tests;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.SwingUtilities;

import course_generator.utils.InternetConnectivityMonitor;

/**
 * Headless regression for the post-sleep UI freeze: a stalled HTTP probe
 * must never occupy the Swing Event Dispatch Thread or trigger overlapping
 * connectivity requests.
 */
public final class InternetConnectivityMonitorSmoke {
    private static void check(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        final CountDownLatch probeStarted = new CountDownLatch(1);
        final CountDownLatch allowProbeToFinish = new CountDownLatch(1);
        final CountDownLatch submittedOnEdt = new CountDownLatch(1);
        final CountDownLatch edtResponsive = new CountDownLatch(1);
        final CountDownLatch resultDelivered = new CountDownLatch(1);
        final CountDownLatch secondDelivered = new CountDownLatch(1);
        final AtomicInteger probeCalls = new AtomicInteger();
        final AtomicInteger resultCalls = new AtomicInteger();
        final AtomicBoolean submitted = new AtomicBoolean();
        final AtomicBoolean resultOnEdt = new AtomicBoolean(true);
        final AtomicBoolean lastResult = new AtomicBoolean(false);

        InternetConnectivityMonitor monitor = new InternetConnectivityMonitor(
                () -> {
                    int call = probeCalls.incrementAndGet();
                    if (call == 1) {
                        probeStarted.countDown();
                        try {
                            if (!allowProbeToFinish.await(10, TimeUnit.SECONDS))
                                throw new IllegalStateException("Synthetic network request timed out");
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                    }
                    return true;
                },
                reachable -> {
                    resultOnEdt.set(resultOnEdt.get() && SwingUtilities.isEventDispatchThread());
                    lastResult.set(reachable);
                    if (resultCalls.incrementAndGet() == 1)
                        resultDelivered.countDown();
                    else
                        secondDelivered.countDown();
                });
        try {
            // Queue a request on the EDT. It must return *before* the fake
            // network probe is allowed to complete.
            SwingUtilities.invokeLater(() -> {
                submitted.set(monitor.requestCheck());
                submittedOnEdt.countDown();
            });
            check(submittedOnEdt.await(2, TimeUnit.SECONDS),
                    "Request blocked the Swing Event Dispatch Thread");
            check(submitted.get(), "Initial connectivity check not scheduled");
            check(probeStarted.await(2, TimeUnit.SECONDS),
                    "Background network probe did not start");
            check(!monitor.requestCheck() && !monitor.requestCheck(),
                    "Concurrent Internet probes were scheduled");
            check(probeCalls.get() == 1, "More than one probe ran concurrently");

            // A separate GUI event must still run while the network is hung.
            SwingUtilities.invokeLater(edtResponsive::countDown);
            check(edtResponsive.await(2, TimeUnit.SECONDS),
                    "Swing EDT stopped responding during blocked network read");

            allowProbeToFinish.countDown();
            check(resultDelivered.await(3, TimeUnit.SECONDS),
                    "Connectivity result was not delivered");
            check(resultOnEdt.get() && lastResult.get(),
                    "Connectivity result was wrong or did not arrive on EDT");

            check(monitor.requestCheck(), "Subsequent probe was not accepted");
            check(secondDelivered.await(3, TimeUnit.SECONDS),
                    "Second connectivity result was not delivered");
            check(probeCalls.get() == 2 && resultCalls.get() == 2,
                    "Lost/duplicated completed connectivity results");
            System.out.println("Internet probe non-blocking EDT, single-flight and EDT callback: OK");
        } finally {
            allowProbeToFinish.countDown();
            monitor.close();
        }
    }
}
