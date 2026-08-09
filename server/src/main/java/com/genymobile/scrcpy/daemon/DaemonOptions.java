package com.genymobile.scrcpy.daemon;

public class DaemonOptions {

    private int port = -1;
    private boolean daemonMode = false;
    private String bindAddress = "127.0.0.1";

    public DaemonOptions() {
    }

    public static DaemonOptions parse(String... args) {
        DaemonOptions opts = new DaemonOptions();
        for (String arg : args) {
            if ("--daemon".equals(arg)) {
                opts.setDaemonMode(true);
            } else if (arg.startsWith("--port=")) {
                try {
                    int port = Integer.parseInt(arg.substring("--port=".length()));
                    if (port >= 1024 && port <= 65535) {
                        opts.setPort(port);
                    }
                } catch (NumberFormatException ignored) {
                }
            } else if (arg.startsWith("--bind_address=")) {
                String address = arg.substring("--bind_address=".length());
                if (!address.isEmpty()) {
                    opts.setBindAddress(address);
                }
            }
        }
        return opts;
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
