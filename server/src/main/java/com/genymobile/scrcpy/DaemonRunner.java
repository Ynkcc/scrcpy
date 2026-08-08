package com.genymobile.scrcpy;

import com.genymobile.scrcpy.model.ConfigurationException;
import com.genymobile.scrcpy.util.Ln;

import java.io.IOException;

public final class DaemonRunner {

    private DaemonRunner() {
    }

    public interface ServerRunnable {
        void run(Options options, DaemonOptions daemonOptions) throws IOException, ConfigurationException;
    }

    public static void run(Options options, DaemonOptions daemonOptions, ServerRunnable scrcpyRunner) {
        try {
            while (true) {
                try {
                    scrcpyRunner.run(options, daemonOptions);
                } catch (ConfigurationException e) {
                    Ln.w("Configuration error in daemon loop, exiting: " + e.getMessage());
                    break;
                } catch (RuntimeException e) {
                    Ln.e("Fatal runtime error in daemon loop, exiting", e);
                    break;
                } catch (Exception e) {
                    Ln.w("Session ended, waiting for new client...", e);
                }
                Ln.i("Daemon: waiting for next client connection...");
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            DaemonManager.getInstance().releaseAll();
        }
    }
}
