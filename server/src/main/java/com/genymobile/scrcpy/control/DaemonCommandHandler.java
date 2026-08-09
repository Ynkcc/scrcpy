package com.genymobile.scrcpy.control;

import com.genymobile.scrcpy.DaemonManager;
import com.genymobile.scrcpy.VideoController;
import com.genymobile.scrcpy.device.Device;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.video.SurfaceCapture;
import com.genymobile.scrcpy.video.ScreenCapture;
import com.genymobile.scrcpy.wrappers.ServiceManager;

import android.os.Parcel;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DaemonCommandHandler {

    private static class HandlerEntry {
        final ExecutionPolicy policy;
        final CommandHandler handler;

        HandlerEntry(ExecutionPolicy policy, CommandHandler handler) {
            this.policy = policy;
            this.handler = handler;
        }
    }

    private final DeviceMessageSender sender;
    private final Controller controller;
    private final CommandContext context;

    private final ExecutorService interactiveExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService lifecycleExecutor = Executors.newFixedThreadPool(2);
    private final Map<Integer, HandlerEntry> registry = new HashMap<>();

    private VideoController videoController;

    public DaemonCommandHandler(DeviceMessageSender sender, Controller controller) {
        this.sender = sender;
        this.controller = controller;
        this.context = new CommandContext(sender, controller, null) {
            @Override
            public VideoController getVideoController() {
                return videoController;
            }
        };
        initRegistry();
    }

    public void setVideoController(VideoController videoController) {
        this.videoController = videoController;
    }

    public void close() {
        interactiveExecutor.shutdownNow();
        lifecycleExecutor.shutdownNow();
    }

    public boolean isExitDaemonRequested() {
        return DaemonManager.getInstance().isExitDaemonRequested();
    }

    private void register(int type, ExecutionPolicy policy, CommandHandler handler) {
        registry.put(type, new HandlerEntry(policy, handler));
    }

    private void initRegistry() {
        register(ControlMessage.TYPE_CREATE_VIRTUAL_DISPLAY, ExecutionPolicy.SLOW, (msg, ctx) -> {
            int newDisplayId = DaemonManager.getInstance().createVirtualDisplay(
                    msg.getText(), msg.getWidth(), msg.getHeight(), msg.getDpi(), msg.getFlags());
            if (newDisplayId == -1) {
                throw new RuntimeException("FAILED");
            }
            sendSuccessResponse(msg, newDisplayId, "OK");
        });

        register(ControlMessage.TYPE_RELEASE_VIRTUAL_DISPLAY, ExecutionPolicy.SLOW, (msg, ctx) -> {
            boolean ok = DaemonManager.getInstance().releaseVirtualDisplay(msg.getDisplayId());
            if (!ok) {
                throw new RuntimeException("FAILED");
            }
            sendSuccessResponse(msg, msg.getDisplayId(), "OK");
        });

        register(ControlMessage.TYPE_RESIZE_VIRTUAL_DISPLAY, ExecutionPolicy.SLOW, (msg, ctx) -> {
            boolean ok = DaemonManager.getInstance().resizeVirtualDisplay(
                    msg.getDisplayId(), msg.getWidth(), msg.getHeight(), msg.getDpi());
            if (!ok) {
                throw new RuntimeException("FAILED");
            }
            sendSuccessResponse(msg, msg.getDisplayId(), "OK");
        });

        register(ControlMessage.TYPE_START_ACTIVITY, ExecutionPolicy.SLOW, (msg, ctx) -> {
            int result = DaemonManager.getInstance().startActivity(msg.getText(), msg.getDisplayId());
            if (result < 0) {
                throw new RuntimeException("FAILED");
            }
            sendSuccessResponse(msg, msg.getDisplayId(), "OK");
        });

        register(ControlMessage.TYPE_GET_ACTIVE_DISPLAY_IDS, ExecutionPolicy.FAST, (msg, ctx) -> {
            int[] ids = DaemonManager.getInstance().getActiveDisplayIds();
            if (ctx.getSender() != null) {
                DeviceMessage response = DeviceMessage.createActiveDisplaysResponse(msg.getSequence(), ids);
                ctx.getSender().send(response);
            }
        });

        register(ControlMessage.TYPE_INJECT_INPUT_EVENT_WITH_DISPLAY_ID, ExecutionPolicy.FAST, (msg, ctx) -> {
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

            int targetDisplayId = msg.getDisplayId();
            boolean ok;

            if (targetDisplayId == 0) {
                ok = ServiceManager.getInputManager().injectInputEvent(event, Device.INJECT_MODE_ASYNC);
            } else {
                com.genymobile.scrcpy.wrappers.InputManager.setDisplayId(event, targetDisplayId);
                ok = Device.injectEvent(event, targetDisplayId, Device.INJECT_MODE_ASYNC);
            }

            Ln.d("handleInjectInputEvent: displayId=" + targetDisplayId + ", result=" + ok);

            if (!ok) {
                throw new RuntimeException("FAILED");
            }
            sendSuccessResponse(msg, targetDisplayId, "OK");
        });

        register(ControlMessage.TYPE_SWITCH_DISPLAY, ExecutionPolicy.FAST, (msg, ctx) -> {
            int displayId = msg.getDisplayId();
            boolean isValid = displayId == 0 || DaemonManager.getInstance().hasDisplay(displayId);
            if (!isValid) {
                throw new RuntimeException("Display not found: " + displayId);
            }
            DaemonManager.getInstance().setTargetDisplayId(displayId);
            if (ctx.getController() != null) {
                SurfaceCapture sc = ctx.getController().getSurfaceCapture();
                if (sc instanceof ScreenCapture) {
                    ((ScreenCapture) sc).setDisplayId(displayId);
                }
            }
            sendSuccessResponse(msg, displayId, "OK");
        });

        register(ControlMessage.TYPE_EXIT_DAEMON, ExecutionPolicy.FAST, (msg, ctx) -> {
            Ln.i("handleExitDaemon: Quit request received, replying OK and shutting down...");
            sendSuccessResponse(msg, -1, "OK");

            new Thread(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
                DaemonManager.getInstance().requestExitDaemon();
                close();
            }).start();
        });

        register(ControlMessage.TYPE_START_VIDEO_STREAM, ExecutionPolicy.SLOW, (msg, ctx) -> {
            if (ctx.getVideoController() == null) {
                throw new RuntimeException("Video controller not available");
            }
            int displayId = msg.getDisplayId();
            boolean ok = ctx.getVideoController().startVideoStream(displayId);
            if (!ok) {
                throw new RuntimeException("FAILED");
            }
            sendSuccessResponse(msg, displayId, "OK");
        });

        register(ControlMessage.TYPE_STOP_VIDEO_STREAM, ExecutionPolicy.SLOW, (msg, ctx) -> {
            if (ctx.getVideoController() == null) {
                throw new RuntimeException("Video controller not available");
            }
            boolean ok = ctx.getVideoController().stopVideoStream();
            if (!ok) {
                throw new RuntimeException("FAILED");
            }
            sendSuccessResponse(msg, -1, "OK");
        });
    }

    public boolean handle(ControlMessage msg) {
        HandlerEntry entry = registry.get(msg.getType());
        if (entry == null) {
            return false;
        }

        if (interactiveExecutor.isShutdown() || lifecycleExecutor.isShutdown()) {
            Ln.w("DaemonCommandHandler: executor already shutdown, ignoring message type=" + msg.getType());
            return false;
        }

        Runnable task = () -> {
            try {
                entry.handler.handle(msg, context);
            } catch (Throwable t) {
                Ln.e("Execution of command type=" + msg.getType() + " failed", t);
                sendErrorResponse(msg, t);
            }
        };

        if (entry.policy == ExecutionPolicy.FAST) {
            interactiveExecutor.submit(task);
        } else {
            lifecycleExecutor.submit(task);
        }
        return true;
    }

    private void sendSuccessResponse(ControlMessage msg, int extraData, String text) {
        if (sender != null) {
            try {
                DeviceMessage response = DeviceMessage.createGenericResponse(msg.getSequence(), 0, extraData, text);
                sender.send(response);
            } catch (Exception e) {
                Ln.e("Failed to send response", e);
            }
        }
    }

    private void sendErrorResponse(ControlMessage msg, Throwable t) {
        if (sender != null) {
            try {
                String errorMsg = t.getMessage() != null ? t.getMessage() : t.toString();
                DeviceMessage response = DeviceMessage.createGenericResponse(msg.getSequence(), -1, -1, errorMsg);
                sender.send(response);
            } catch (Exception e) {
                Ln.e("Failed to send error response", e);
            }
        }
    }
}
