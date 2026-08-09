package com.genymobile.scrcpy;

public class DaemonOptions {

    private int port = -1;
    private boolean daemonMode = false;
    private String bindAddress = "127.0.0.1";

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

    public String getBindAddress() {
        return bindAddress;
    }

    public void setBindAddress(String bindAddress) {
        this.bindAddress = bindAddress;
    }
}
