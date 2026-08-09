package com.genymobile.scrcpy.daemon;

public final class DaemonExitCoordinator {

    private final DaemonServer server;
    private volatile boolean exitRequested = false;

    public DaemonExitCoordinator(DaemonServer server) {
        this.server = server;
    }

    public void requestExit() {
        this.exitRequested = true;
        if (server != null) {
            server.requestExit();
        }
    }

    public boolean isExitRequested() {
        return exitRequested;
    }
}
