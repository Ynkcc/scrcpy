package com.genymobile.scrcpy.daemon.control;

import com.genymobile.scrcpy.control.ControlMessage;
import com.genymobile.scrcpy.control.ControlMessageExtension;
import com.genymobile.scrcpy.control.Controller;
import com.genymobile.scrcpy.control.DeviceMessage;
import com.genymobile.scrcpy.control.DeviceMessageSender;
import com.genymobile.scrcpy.control.DaemonDeviceMessages;
import com.genymobile.scrcpy.control.DaemonControlMessages;
import com.genymobile.scrcpy.device.Device;
import com.genymobile.scrcpy.daemon.display.VirtualDisplayRegistry;
import com.genymobile.scrcpy.daemon.display.DisplaySurfaceBroker;
import com.genymobile.scrcpy.daemon.display.ActivityLauncher;
import com.genymobile.scrcpy.daemon.DaemonExitCoordinator;
import com.genymobile.scrcpy.daemon.VideoController;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.video.ScreenCapture;
import com.genymobile.scrcpy.video.SurfaceCapture;

import android.os.Parcel;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public final class DaemonCommandHandler implements ControlMessageExtension {

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

    private final VirtualDisplayRegistry registry;
    private final DaemonExitCoordinator exitCoordinator;

    private final ExecutorService interactiveExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService lifecycleExecutor = Executors.newFixedThreadPool(2);
    private final Map<Integer, HandlerEntry> registryMap = new HashMap<>();

    public DaemonCommandHandler(Controller controller,
                                VirtualDisplayRegistry registry, DaemonExitCoordinator exitCoordinator,
                                VideoController videoController) {
        this.controller = controller;
        this.sender = controller.getDeviceMessageSender();
        this.registry = registry;
        this.exitCoordinator = exitCoordinator;
        this.context = new CommandContext(sender, controller, videoController);
        initRegistry();
    }

    public void close() {
        interactiveExecutor.shutdownNow();
        lifecycleExecutor.shutdownNow();
    }

    private void register(int type, ExecutionPolicy policy, CommandHandler handler) {
        registryMap.put(type, new HandlerEntry(policy, handler));
    }

    private void initRegistry() {
        register(DaemonControlMessages.TYPE_CREATE_VIRTUAL_DISPLAY, ExecutionPolicy.SLOW, (msg, ctx) -> {
            int newDisplayId = registry.createVirtualDisplay(
                    msg.getText(), msg.getWidth(), msg.getHeight(), msg.getDpi(), msg.getFlags());
            if (newDisplayId == -1) {
                throw new RuntimeException("FAILED");
            }
            sendSuccessResponse(msg, newDisplayId, "OK");
        });

        register(DaemonControlMessages.TYPE_RELEASE_VIRTUAL_DISPLAY, ExecutionPolicy.SLOW, (msg, ctx) -> {
            boolean ok = registry.releaseVirtualDisplay(msg.getDisplayId());
            if (!ok) {
                throw new RuntimeException("FAILED");
            }
            sendSuccessResponse(msg, msg.getDisplayId(), "OK");
        });

        register(DaemonControlMessages.TYPE_RESIZE_VIRTUAL_DISPLAY, ExecutionPolicy.SLOW, (msg, ctx) -> {
            boolean ok = registry.resizeVirtualDisplay(
                    msg.getDisplayId(), msg.getWidth(), msg.getHeight(), msg.getDpi());
            if (!ok) {
                throw new RuntimeException("FAILED");
            }
            sendSuccessResponse(msg, msg.getDisplayId(), "OK");
        });

        register(DaemonControlMessages.TYPE_START_ACTIVITY, ExecutionPolicy.SLOW, (msg, ctx) -> {
            int result = ActivityLauncher.startActivity(msg.getText(), msg.getDisplayId());
            if (result < 0) {
                throw new RuntimeException("FAILED");
            }
            sendSuccessResponse(msg, msg.getDisplayId(), "OK");
        });

        register(DaemonControlMessages.TYPE_GET_ACTIVE_DISPLAY_IDS, ExecutionPolicy.FAST, (msg, ctx) -> {
            int[] ids = registry.getActiveDisplayIds();
            if (ctx.getSender() != null) {
                DeviceMessage response = DaemonDeviceMessages.createActiveDisplaysResponse(msg.getSequence(), ids);
                ctx.getSender().send(response);
            }
        });

        register(DaemonControlMessages.TYPE_INJECT_INPUT_EVENT_WITH_DISPLAY_ID, ExecutionPolicy.FAST, (msg, ctx) -> {
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
            boolean ok = Device.injectEvent(event, targetDisplayId, Device.INJECT_MODE_ASYNC);

            Ln.d("handleInjectInputEvent: displayId=" + targetDisplayId
                    + ", isKey=" + msg.isKeyEvent()
                    + ", result=" + ok);

            if (!ok && targetDisplayId == 0 && event instanceof MotionEvent) {
                MotionEvent me = (MotionEvent) event;
                float x = me.getX();
                float y = me.getY();
                String inputCmd = "input tap " + (int) x + " " + (int) y;
                try {
                    Ln.d("handleInjectInputEvent: fallback to shell: " + inputCmd);
                    Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", inputCmd});
                    p.waitFor();
                    ok = (p.exitValue() == 0);
                    Ln.d("handleInjectInputEvent: shell result=" + ok + " (exit=" + p.exitValue() + ")");
                } catch (Throwable t) {
                    Ln.w("handleInjectInputEvent: shell fallback failed: " + t.getMessage());
                }
            }

            if (!ok) {
                throw new RuntimeException("FAILED");
            }
            sendSuccessResponse(msg, targetDisplayId, "OK");
        });

        register(DaemonControlMessages.TYPE_SWITCH_DISPLAY, ExecutionPolicy.FAST, (msg, ctx) -> {
            int displayId = msg.getDisplayId();
            boolean isValid = displayId == 0 || registry.hasDisplay(displayId);
            if (!isValid) {
                throw new RuntimeException("Display not found: " + displayId);
            }

            if (ctx.getController() != null) {
                SurfaceCapture sc = ctx.getController().getSurfaceCapture();
                if (sc instanceof ScreenCapture) {
                    ((ScreenCapture) sc).setDisplayId(displayId);
                }
            }
            sendSuccessResponse(msg, displayId, "OK");
        });

        register(DaemonControlMessages.TYPE_EXIT_DAEMON, ExecutionPolicy.FAST, (msg, ctx) -> {
            Ln.i("handleExitDaemon: Quit request received, replying OK and shutting down...");
            sendSuccessResponse(msg, -1, "OK");

            new Thread(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
                exitCoordinator.requestExit();
                close();
            }).start();
        });

        register(DaemonControlMessages.TYPE_START_VIDEO_STREAM, ExecutionPolicy.SLOW, (msg, ctx) -> {
            if (ctx.getVideoController() == null) {
                throw new RuntimeException("Video controller not available");
            }
            int displayId = msg.getDisplayId();
            boolean isValid = displayId == 0 || registry.hasDisplay(displayId);
            if (!isValid) {
                throw new RuntimeException("Display not found: " + displayId);
            }
            boolean ok = ctx.getVideoController().startVideoStream(displayId);
            if (!ok) {
                throw new RuntimeException("FAILED");
            }
            sendSuccessResponse(msg, displayId, "OK");
        });

        register(DaemonControlMessages.TYPE_STOP_VIDEO_STREAM, ExecutionPolicy.SLOW, (msg, ctx) -> {
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

    @Override
    public boolean handle(ControlMessage msg) throws IOException {
        HandlerEntry entry = registryMap.get(msg.getType());
        if (entry == null) {
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

        // submit() is the source of truth for executor state. The previous
        // isShutdown() check was racy (close() could shutdownNow() between the
        // check and submit), and an uncaught RejectedExecutionException would
        // propagate through Controller.handleEvent and terminate the
        // control-recv thread. Catch it here and report the message as
        // unhandled instead.
        try {
            if (entry.policy == ExecutionPolicy.FAST) {
                interactiveExecutor.submit(task);
            } else {
                lifecycleExecutor.submit(task);
            }
        } catch (RejectedExecutionException e) {
            Ln.w("DaemonCommandHandler: executor rejected message type=" + msg.getType() + " (shutting down?)");
            return false;
        }
        return true;
    }

    private void sendSuccessResponse(ControlMessage msg, int extraData, String text) {
        if (sender != null) {
            try {
                DeviceMessage response = DaemonDeviceMessages.createGenericResponse(msg.getSequence(), 0, extraData, text);
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
                DeviceMessage response = DaemonDeviceMessages.createGenericResponse(msg.getSequence(), -1, -1, errorMsg);
                sender.send(response);
            } catch (Exception e) {
                Ln.e("Failed to send error response", e);
            }
        }
    }
}
