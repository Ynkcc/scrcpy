package com.genymobile.scrcpy.daemon.control;

import com.genymobile.scrcpy.control.DeviceMessage;
import com.genymobile.scrcpy.display.DisplayInfo;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class DaemonDeviceMessageWriter {

    private DaemonDeviceMessageWriter() {
    }

    public static boolean write(DeviceMessage msg, DataOutputStream dos) throws IOException {
        int type = msg.getType();
        switch (type) {
            case DaemonDeviceMessages.TYPE_RESPONSE_GENERIC:
                dos.writeLong(msg.getSequence());
                dos.writeInt(msg.getStatusCode());
                dos.writeInt(msg.getDisplayId());
                String responseText = msg.getText();
                byte[] responseBytes = responseText != null ? responseText.getBytes(StandardCharsets.UTF_8) : new byte[0];
                dos.writeInt(responseBytes.length);
                dos.write(responseBytes);
                return true;
            case DaemonDeviceMessages.TYPE_RESPONSE_ACTIVE_DISPLAYS:
                dos.writeLong(msg.getSequence());
                int[] ids = msg.getDisplayIds();
                dos.writeInt(ids.length);
                for (int id : ids) {
                    dos.writeInt(id);
                }
                return true;
            case DaemonDeviceMessages.TYPE_RESPONSE_ACTIVE_DISPLAY_INFOS:
                dos.writeLong(msg.getSequence());
                DisplayInfo[] infos = msg.getDisplayInfos();
                dos.writeInt(infos.length);
                for (DisplayInfo info : infos) {
                    dos.writeInt(info.getDisplayId());
                    dos.writeInt(info.getSize().getWidth());
                    dos.writeInt(info.getSize().getHeight());
                    dos.writeInt(info.getDpi());
                    dos.writeInt(info.getRotation());
                }
                return true;
            default:
                return false;
        }
    }
}
