package com.genymobile.scrcpy.daemon;

import java.util.ArrayList;
import java.util.List;

public final class DaemonArgs {

    private DaemonArgs() {
    }

    /**
     * 过滤掉所有的 daemon 专属参数，还原出供上游 Options.parse 使用的参数数组
     */
    public static String[] strip(String[] args) {
        if (args == null) {
            return new String[0];
        }
        List<String> list = new ArrayList<>();
        for (String arg : args) {
            if ("--daemon".equals(arg) || arg.startsWith("--port=") || arg.startsWith("--bind_address=")) {
                continue;
            }
            list.add(arg);
        }
        return list.toArray(new String[0]);
    }

    /**
     * 将参数数组中的 --display-id=X 或者是 --display-id Y 替换/追加为指定的 displayId，
     * 从而可以使用原生的 Options.parse 重新解析出带有指定 displayId 的 Options
     */
    public static String[] changeDisplayId(String[] args, int displayId) {
        String[] stripped = strip(args);
        List<String> list = new ArrayList<>();
        boolean found = false;

        for (int i = 0; i < stripped.length; i++) {
            String arg = stripped[i];
            if (arg.startsWith("--display-id=")) {
                list.add("--display-id=" + displayId);
                found = true;
            } else if ("--display-id".equals(arg)) {
                list.add("--display-id");
                list.add(String.valueOf(displayId));
                i++; // 跳过下一个原有的 displayId 字符串
                found = true;
            } else {
                list.add(arg);
            }
        }

        if (!found) {
            list.add("--display-id=" + displayId);
        }

        return list.toArray(new String[0]);
    }
}
