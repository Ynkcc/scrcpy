package com.genymobile.scrcpy.control;

import com.genymobile.scrcpy.DaemonManager;
import com.genymobile.scrcpy.device.Device;
import com.genymobile.scrcpy.util.Ln;

import android.os.Parcel;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

public class DaemonCommandHandler {

    private final DeviceMessageSender sender;

    public DaemonCommandHandler(DeviceMessageSender sender) {
        this.sender = sender;
    }

    public boolean handle(ControlMessage msg) {
        switch (msg.getType()) {
            case ControlMessage.TYPE_CREATE_VIRTUAL_DISPLAY:
                handleCreateVirtualDisplay(msg);
                return true;
            case ControlMessage.TYPE_RELEASE_VIRTUAL_DISPLAY:
                handleReleaseVirtualDisplay(msg);
                return true;
            case ControlMessage.TYPE_RESIZE_VIRTUAL_DISPLAY:
                handleResizeVirtualDisplay(msg);
                return true;
            case ControlMessage.TYPE_START_ACTIVITY:
                handleDaemonStartActivity(msg);
                return true;
            case ControlMessage.TYPE_GET_ACTIVE_DISPLAY_IDS:
                handleGetActiveDisplayIds(msg);
                return true;
            case ControlMessage.TYPE_INJECT_INPUT_EVENT_WITH_DISPLAY_ID:
                handleInjectInputEventWithDisplayId(msg);
                return true;
            case ControlMessage.TYPE_SWITCH_DISPLAY:
                handleSwitchDisplay(msg);
                return true;
            default:
                return false;
        }
    }

    private void handleCreateVirtualDisplay(ControlMessage msg) {
        try {
            int newDisplayId = DaemonManager.getInstance().createVirtualDisplay(
                    msg.getText(), msg.getWidth(), msg.getHeight(), msg.getDpi(), msg.getFlags());
            if (sender != null) {
                DeviceMessage response = DeviceMessage.createGenericResponse(
                        msg.getSequence(), newDisplayId != -1 ? 0 : -1, newDisplayId, newDisplayId != -1 ? "OK" : "FAILED");
                sender.send(response);
            }
        } catch (Exception e) {
            Ln.e("handleCreateVirtualDisplay failed", e);
            if (sender != null) {
                DeviceMessage response = DeviceMessage.createGenericResponse(msg.getSequence(), -1, -1, e.getMessage());
                sender.send(response);
            }
        }
    }

    private void handleReleaseVirtualDisplay(ControlMessage msg) {
        try {
            boolean ok = DaemonManager.getInstance().releaseVirtualDisplay(msg.getDisplayId());
            if (sender != null) {
                DeviceMessage response = DeviceMessage.createGenericResponse(msg.getSequence(), ok ? 0 : -1, msg.getDisplayId(), ok ? "OK" : "FAILED");
                sender.send(response);
            }
        } catch (Exception e) {
            Ln.e("handleReleaseVirtualDisplay failed", e);
            if (sender != null) {
                DeviceMessage response = DeviceMessage.createGenericResponse(msg.getSequence(), -1, msg.getDisplayId(), e.getMessage());
                sender.send(response);
            }
        }
    }

    private void handleResizeVirtualDisplay(ControlMessage msg) {
        try {
            boolean ok = DaemonManager.getInstance().resizeVirtualDisplay(
                    msg.getDisplayId(), msg.getWidth(), msg.getHeight(), msg.getDpi());
            if (sender != null) {
                DeviceMessage response = DeviceMessage.createGenericResponse(msg.getSequence(), ok ? 0 : -1, msg.getDisplayId(), ok ? "OK" : "FAILED");
                sender.send(response);
            }
        } catch (Exception e) {
            Ln.e("handleResizeVirtualDisplay failed", e);
            if (sender != null) {
                DeviceMessage response = DeviceMessage.createGenericResponse(msg.getSequence(), -1, msg.getDisplayId(), e.getMessage());
                sender.send(response);
            }
        }
    }

    private void handleDaemonStartActivity(ControlMessage msg) {
        try {
            int result = DaemonManager.getInstance().startActivity(msg.getText(), msg.getDisplayId());
            if (sender != null) {
                DeviceMessage response = DeviceMessage.createGenericResponse(msg.getSequence(), result, msg.getDisplayId(), result >= 0 ? "OK" : "FAILED");
                sender.send(response);
            }
        } catch (Exception e) {
            Ln.e("handleDaemonStartActivity failed", e);
            if (sender != null) {
                DeviceMessage response = DeviceMessage.createGenericResponse(msg.getSequence(), -1, msg.getDisplayId(), e.getMessage());
                sender.send(response);
            }
        }
    }

    private void handleGetActiveDisplayIds(ControlMessage msg) {
        try {
            int[] ids = DaemonManager.getInstance().getActiveDisplayIds();
            if (sender != null) {
                DeviceMessage response = DeviceMessage.createActiveDisplaysResponse(msg.getSequence(), ids);
                sender.send(response);
            }
        } catch (Exception e) {
            Ln.e("handleGetActiveDisplayIds failed", e);
            if (sender != null) {
                DeviceMessage response = DeviceMessage.createGenericResponse(msg.getSequence(), -1, -1, e.getMessage());
                sender.send(response);
            }
        }
    }

    private void handleInjectInputEventWithDisplayId(ControlMessage msg) {
        try {
            Parcel parcel = Parcel.obtain();
            parcel.unmarshall(msg.getData(), 0, msg.getData().length);
            parcel.setDataPosition(0);
            InputEvent event;
            if (msg.isKeyEvent()) {
                event = KeyEvent.CREATOR.createFromParcel(parcel);
            } else {
                event = MotionEvent.CREATOR.createFromParcel(parcel);
            }
            parcel.recycle();

            boolean ok = Device.injectEvent(event, msg.getDisplayId(), Device.INJECT_MODE_ASYNC);
            if (sender != null) {
                DeviceMessage response = DeviceMessage.createGenericResponse(msg.getSequence(), ok ? 0 : -1, msg.getDisplayId(), ok ? "OK" : "FAILED");
                sender.send(response);
            }
        } catch (Exception e) {
            Ln.e("Failed to inject input event with displayId", e);
            if (sender != null) {
                DeviceMessage response = DeviceMessage.createGenericResponse(msg.getSequence(), -1, msg.getDisplayId(), e.getMessage());
                sender.send(response);
            }
        }
    }

    private void handleSwitchDisplay(ControlMessage msg) {
        try {
            DaemonManager.getInstance().setTargetDisplayId(msg.getDisplayId());
            if (sender != null) {
                DeviceMessage response = DeviceMessage.createGenericResponse(msg.getSequence(), 0, msg.getDisplayId(), "OK");
                sender.send(response);
            }
        } catch (Exception e) {
            Ln.e("handleSwitchDisplay failed", e);
            if (sender != null) {
                DeviceMessage response = DeviceMessage.createGenericResponse(msg.getSequence(), -1, msg.getDisplayId(), e.getMessage());
                sender.send(response);
            }
        }
    }
}
