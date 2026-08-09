package com.genymobile.scrcpy.daemon;

import com.genymobile.scrcpy.AsyncProcessor;
import com.genymobile.scrcpy.control.Controller;
import com.genymobile.scrcpy.util.Ln;

/**
 * Runs the {@link Controller} control loop on a dedicated thread.
 *
 * <p>Encapsulates the {@code ctrlThread} management previously inlined in
 * {@code ClientSession.run()}, so the session orchestrator only deals with
 * start/join/stop and a termination callback.
 */
public final class ControlLoopRunner {

    private final int sessionId;
    private Thread thread;

    public ControlLoopRunner(int sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * Start the control loop. The {@code controller} runs on a daemon thread named
     * {@code ctrl-<sessionId>}; on termination {@code onTerminated} is invoked.
     */
    public void start(Controller controller, AsyncProcessor.TerminationListener onTerminated) {
        thread = new Thread(() -> {
            try {
                Ln.i("Session[" + sessionId + "]: calling controller.start()");
                controller.start(onTerminated);
                Ln.i("Session[" + sessionId + "]: controller.start() returned, joining...");
                controller.join();
                Ln.i("Session[" + sessionId + "]: controller.join() returned");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                Ln.e("Session[" + sessionId + "]: ctrlThread error", t);
            }
        }, "ctrl-" + sessionId);
        thread.setDaemon(true);
        thread.start();
    }

    public void join() throws InterruptedException {
        if (thread != null) {
            thread.join();
            Ln.i("Session[" + sessionId + "]: ctrlThread.join() returned");
        }
    }
}
