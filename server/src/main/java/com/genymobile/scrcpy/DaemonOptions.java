package com.genymobile.scrcpy;

public class DaemonOptions {

    private int port = -1;
    private boolean daemonMode = false;

    public DaemonOptions() {
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public boolean isDaemonMode() {
        return daemonMode;
    }

    public void setDaemonMode(boolean daemonMode) {
        this.daemonMode = daemonMode;
    }
}
