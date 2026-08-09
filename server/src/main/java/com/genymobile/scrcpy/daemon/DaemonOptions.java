package com.genymobile.scrcpy.daemon;

public class DaemonOptions {

    private int port = -1;
    private boolean daemonMode = false;
    private String bindAddress = "127.0.0.1";

    public DaemonOptions() {
    }

    public static DaemonOptions parse(String... args) {
        DaemonOptions opts = new DaemonOptions();
        if (args == null) {
            return opts;
        }
        // 与 scrcpy 原生参数一致，统一采用 key=value 形式：
        //   daemon=true  daemon_port=27183  daemon_bind_address=127.0.0.1
        for (String arg : args) {
            int eq = arg.indexOf('=');
            if (eq == -1) {
                continue;
            }
            String key = arg.substring(0, eq);
            String value = arg.substring(eq + 1);
            switch (key) {
                case "daemon":
                    opts.daemonMode = Boolean.parseBoolean(value);
                    break;
                case "daemon_port":
                    try {
                        int port = Integer.parseInt(value);
                        if (port >= 1024 && port <= 65535) {
                            opts.port = port;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                    break;
                case "daemon_bind_address":
                    if (!value.isEmpty()) {
                        opts.bindAddress = value;
                    }
                    break;
                default:
                    break;
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
