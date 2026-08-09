package com.genymobile.scrcpy.daemon;

import java.util.ArrayList;
import java.util.List;

public final class DaemonArgs {

    private DaemonArgs() {
    }

    /**
     * 过滤掉所有的 daemon 专属参数，还原出供上游 Options.parse 使用的参数数组。
     * daemon 参数均为 key=value 形式：daemon / daemon_port / daemon_bind_address
     */
    public static String[] strip(String[] args) {
        if (args == null) {
            return new String[0];
        }
        List<String> list = new ArrayList<>();
        for (String arg : args) {
            int eq = arg.indexOf('=');
            if (eq != -1) {
                String key = arg.substring(0, eq);
                if (key.equals("daemon") || key.equals("daemon_port") || key.equals("daemon_bind_address")) {
                    continue;
                }
            }
            list.add(arg);
        }
        return list.toArray(new String[0]);
    }

    /**
     * 将参数数组中的 display_id=X 替换/追加为指定的 displayId，
     * 从而可以使用原生的 Options.parse 重新解析出带有指定 displayId 的 Options。
     * 注意：scrcpy 原生参数键为 display_id（下划线、无 -- 前缀），恒为 key=value 形式。
     */
    public static String[] changeDisplayId(String[] args, int displayId) {
        String[] stripped = strip(args);
        List<String> list = new ArrayList<>();
        boolean found = false;

        for (String arg : stripped) {
            if (arg.startsWith("display_id=")) {
                list.add("display_id=" + displayId);
                found = true;
            } else {
                list.add(arg);
            }
        }

        if (!found) {
            list.add("display_id=" + displayId);
        }

        return list.toArray(new String[0]);
    }
}
