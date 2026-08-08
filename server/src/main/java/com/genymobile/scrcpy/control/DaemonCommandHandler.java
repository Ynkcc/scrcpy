package com.genymobile.scrcpy.control;

import com.genymobile.scrcpy.DaemonManager;
import com.genymobile.scrcpy.device.Device;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.video.SurfaceCapture;
import com.genymobile.scrcpy.video.ScreenCapture;

import android.os.Parcel;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DaemonCommandHandler {

    private final DeviceMessageSender sender;
    private final Controller controller;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public DaemonCommandHandler(DeviceMessageSender sender, Controller controller) {
        this.sender = sender;
        this.controller = controller;
    }

    public void close() {
        executor.shutdownNow();
    }

    public boolean handle(ControlMessage msg) {
        switch (msg.getType()) {
            case ControlMessage.TYPE_CREATE_VIRTUAL_DISPLAY:
                executor.submit(() -> handleCreateVirtualDisplay(msg));
                return true;
            case ControlMessage.TYPE_RELEASE_VIRTUAL_DISPLAY:
                executor.submit(() -> handleReleaseVirtualDisplay(msg));
                return true;
            case ControlMessage.TYPE_RESIZE_VIRTUAL_DISPLAY:
                executor.submit(() -> handleResizeVirtualDisplay(msg));
                return true;
            case ControlMessage.TYPE_START_ACTIVITY:
                executor.submit(() -> handleDaemonStartActivity(msg));
                return true;
            case ControlMessage.TYPE_GET_ACTIVE_DISPLAY_IDS:
                executor.submit(() -> handleGetActiveDisplayIds(msg));
                return true;
            case ControlMessage.TYPE_INJECT_INPUT_EVENT_WITH_DISPLAY_ID:
                executor.submit(() -> handleInjectInputEventWithDisplayId(msg));
                return true;
            case ControlMessage.TYPE_SWITCH_DISPLAY:
                executor.submit(() -> handleSwitchDisplay(msg));
                return true;
            case ControlMessage.TYPE_EXIT_DAEMON:
                close();
                handleExitDaemon(msg);
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
        } catch (Throwable t) {
            Ln.e("handleCreateVirtualDisplay failed", t);
            if (sender != null) {
                String errorMsg = t.getMessage() != null ? t.getMessage() : t.toString();
                DeviceMessage response = DeviceMessage.createGenericResponse(msg.getSequence(), -1, -1, errorMsg);
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
        } catch (Throwable t) {
            Ln.e("handleReleaseVirtualDisplay failed", t);
            if (sender != null) {
                String errorMsg = t.getMessage() != null ? t.getMessage() : t.toString();
                DeviceMessage response = DeviceMessage.createGenericResponse(msg.getSequence(), -1, msg.getDisplayId(), errorMsg);
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
        } catch (Throwable t) {
            Ln.e("handleResizeVirtualDisplay failed", t);
            if (sender != null) {
                String errorMsg = t.getMessage() != null ? t.getMessage() : t.toString();
                DeviceMessage response = DeviceMessage.createGenericResponse(msg.getSequence(), -1, msg.getDisplayId(), errorMsg);
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
        } catch (Throwable t) {
            Ln.e("handleDaemonStartActivity failed", t);
            if (sender != null) {
                String errorMsg = t.getMessage() != null ? t.getMessage() : t.toString();
                DeviceMessage response = DeviceMessage.createGenericResponse(msg.getSequence(), -1, msg.getDisplayId(), errorMsg);
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
        } catch (Throwable t) {
            Ln.e("handleGetActiveDisplayIds failed", t);
            if (sender != null) {
                String errorMsg = t.getMessage() != null ? t.getMessage() : t.toString();
                DeviceMessage response = DeviceMessage.createGenericResponse(msg.getSequence(), -1, -1, errorMsg);
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
        } catch (Throwable t) {
            Ln.e("Failed to inject input event with displayId", t);
            if (sender != null) {
                String errorMsg = t.getMessage() != null ? t.getMessage() : t.toString();
                DeviceMessage response = DeviceMessage.createGenericResponse(msg.getSequence(), -1, msg.getDisplayId(), errorMsg);
                sender.send(response);
            }
        }
    }

    private void handleSwitchDisplay(ControlMessage msg) {
        try {
            int displayId = msg.getDisplayId();
            DaemonManager.getInstance().setTargetDisplayId(displayId);
            if (controller != null) {
                SurfaceCapture sc = controller.getSurfaceCapture();
                if (sc instanceof ScreenCapture) {
                    ((ScreenCapture) sc).setDisplayId(displayId);
                }
            }
            if (sender != null) {
                DeviceMessage response = DeviceMessage.createGenericResponse(msg.getSequence(), 0, displayId, "OK");
                sender.send(response);
            }
        } catch (Throwable t) {
            Ln.e("handleSwitchDisplay failed", t);
            if (sender != null) {
                String errorMsg = t.getMessage() != null ? t.getMessage() : t.toString();
                DeviceMessage response = DeviceMessage.createGenericResponse(msg.getSequence(), -1, msg.getDisplayId(), errorMsg);
                sender.send(response);
            }
        }
    }

    private void handleExitDaemon(ControlMessage msg) {
        try {
            Ln.i("handleExitDaemon: Quit request received, replying OK and shutting down...");
            if (sender != null) {
                DeviceMessage response = DeviceMessage.createGenericResponse(msg.getSequence(), 0, -1, "OK");
                sender.send(response);
            }
        } catch (Exception e) {
            Ln.e("handleExitDaemon reply failed", e);
        } finally {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
            throw new RuntimeException("QUIT_DAEMON");
        }
    }
}
