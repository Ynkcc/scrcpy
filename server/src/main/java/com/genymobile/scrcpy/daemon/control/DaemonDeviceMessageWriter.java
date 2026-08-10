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
        // This writer is only reached for daemon response types (invoked from
        // DeviceMessageWriter's default branch), so every message carries a
        // DaemonDeviceMessage payload.
        DaemonDeviceMessage dto = DaemonDeviceMessages.payload(msg);
        switch (type) {
            case DaemonDeviceMessages.TYPE_RESPONSE_GENERIC:
                dos.writeLong(dto.getSequence());
                dos.writeInt(dto.getStatusCode());
                dos.writeInt(dto.getDisplayId());
                String responseText = dto.getText();
                byte[] responseBytes = responseText != null ? responseText.getBytes(StandardCharsets.UTF_8) : new byte[0];
                dos.writeInt(responseBytes.length);
                dos.write(responseBytes);
                return true;
            case DaemonDeviceMessages.TYPE_RESPONSE_ACTIVE_DISPLAYS:
                dos.writeLong(dto.getSequence());
                int[] ids = dto.getDisplayIds();
                dos.writeInt(ids.length);
                for (int id : ids) {
                    dos.writeInt(id);
                }
                return true;
            case DaemonDeviceMessages.TYPE_RESPONSE_ACTIVE_DISPLAY_INFOS:
                dos.writeLong(dto.getSequence());
                DisplayInfo[] infos = dto.getDisplayInfos();
                dos.writeInt(infos.length);
                for (DisplayInfo info : infos) {
                    dos.writeInt(info.getDisplayId());
                    dos.writeInt(info.getSize().getWidth());
                    dos.writeInt(info.getSize().getHeight());
                    dos.writeInt(info.getDpi());
                    dos.writeInt(info.getRotation());
                    dos.writeInt(info.getMirrorDisplayId());
                    dos.writeByte(info.isOwned() ? 1 : 0);
                }
                return true;
            default:
                return false;
        }
    }
}
