package com.genymobile.scrcpy.daemon.control;

import com.genymobile.scrcpy.control.ControlMessage;
import com.genymobile.scrcpy.control.ControlMessageExtension;
import com.genymobile.scrcpy.control.Controller;
import com.genymobile.scrcpy.control.DeviceMessage;
import com.genymobile.scrcpy.control.DeviceMessageSender;
import com.genymobile.scrcpy.device.Device;
import com.genymobile.scrcpy.daemon.display.VirtualDisplayRegistry;
import com.genymobile.scrcpy.daemon.display.ActivityLauncher;
import com.genymobile.scrcpy.daemon.display.RotationController;
import com.genymobile.scrcpy.daemon.DaemonExitCoordinator;
import com.genymobile.scrcpy.daemon.VideoController;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.video.CaptureControl;
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
        private final ExecutionPolicy policy;
        private final CommandHandler handler;

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
    private final RotationController rotationController;

    private final ExecutorService interactiveExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService lifecycleExecutor = Executors.newFixedThreadPool(2);
    private final Map<Integer, HandlerEntry> registryMap = new HashMap<>();

    public DaemonCommandHandler(Controller controller,
                                VirtualDisplayRegistry registry, DaemonExitCoordinator exitCoordinator,
                                VideoController videoController) {
        this(controller != null ? controller.getDeviceMessageSender() : null, controller, registry, exitCoordinator, videoController);
    }

    public DaemonCommandHandler(DeviceMessageSender sender, Controller controller,
                                VirtualDisplayRegistry registry, DaemonExitCoordinator exitCoordinator,
                                VideoController videoController) {
        this.controller = controller;
        this.sender = sender;
        this.registry = registry;
        this.exitCoordinator = exitCoordinator;
        this.rotationController = new RotationController(registry);
        this.context = new CommandContext(sender, controller, videoController);
        initRegistry();
    }

    public void close() {
        interactiveExecutor.shutdownNow();
        lifecycleExecutor.shutdownNow();
    }

    private static DaemonControlMessage payload(ControlMessage msg) {
        return DaemonControlMessages.payload(msg);
    }

    private void register(int type, ExecutionPolicy policy, CommandHandler handler) {
        registryMap.put(type, new HandlerEntry(policy, handler));
    }

    private void initRegistry() {
        register(DaemonControlMessages.TYPE_CREATE_VIRTUAL_DISPLAY, ExecutionPolicy.SLOW, (msg, ctx) -> {
            DaemonControlMessage dto = payload(msg);
            int newDisplayId = registry.createVirtualDisplay(
                    dto.getText(), dto.getWidth(), dto.getHeight(), dto.getDpi(), dto.getFlags());
            if (newDisplayId == -1) {
                throw new RuntimeException("FAILED");
            }
            sendSuccessResponse(msg, newDisplayId, "OK");
        });

        register(DaemonControlMessages.TYPE_RELEASE_VIRTUAL_DISPLAY, ExecutionPolicy.SLOW, (msg, ctx) -> {
            DaemonControlMessage dto = payload(msg);
            boolean ok = registry.releaseVirtualDisplay(dto.getDisplayId());
            if (!ok) {
                throw new RuntimeException("FAILED");
            }
            sendSuccessResponse(msg, dto.getDisplayId(), "OK");
        });

        register(DaemonControlMessages.TYPE_RESIZE_VIRTUAL_DISPLAY, ExecutionPolicy.SLOW, (msg, ctx) -> {
            DaemonControlMessage dto = payload(msg);
            int displayId = dto.getDisplayId();
            boolean ok = registry.resizeVirtualDisplay(
                    displayId, dto.getWidth(), dto.getHeight(), dto.getDpi());
            if (!ok) {
                throw new RuntimeException("FAILED");
            }
            // After a successful resize, explicitly kick the running video encoder
            // pipeline so it picks up the new dimensions. Without this, the
            // encoder may keep producing frames at the old resolution until the
            // next DisplayMonitor event fires (which can be racy on some
            // devices), resulting in a stretched/corrupted/blank picture until
            // the pipeline is torn down.
            if (ctx.getController() != null) {
                SurfaceCapture sc = ctx.getController().getSurfaceCapture();
                if (sc instanceof ScreenCapture) {
                    ScreenCapture screenCapture = (ScreenCapture) sc;
                    int runningDisplayId = screenCapture.getDisplayId();
                    CaptureControl cc = screenCapture.getCaptureControl();
                    if (runningDisplayId == displayId && cc != null) {
                        Ln.i("DaemonCommandHandler: resize displayId=" + displayId
                                + " — requesting encoder pipeline reset for new size "
                                + dto.getWidth() + "x" + dto.getHeight());
                        cc.reset(CaptureControl.RESET_REASON_CLIENT_RESIZED
                                | CaptureControl.RESET_REASON_DISPLAY_PROPERTIES_CHANGED);
                    }
                }
            }
            sendSuccessResponse(msg, displayId, "OK");
        });

        register(DaemonControlMessages.TYPE_START_ACTIVITY, ExecutionPolicy.SLOW, (msg, ctx) -> {
            DaemonControlMessage dto = payload(msg);
            int result = ActivityLauncher.startActivity(dto.getText(), dto.getDisplayId());
            if (result < 0) {
                throw new RuntimeException("FAILED");
            }
            sendSuccessResponse(msg, dto.getDisplayId(), "OK");
        });

        register(DaemonControlMessages.TYPE_GET_ACTIVE_DISPLAY_IDS, ExecutionPolicy.FAST, (msg, ctx) -> {
            int[] ids = registry.getActiveDisplayIds();
            if (ctx.getSender() != null) {
                DeviceMessage response = DaemonDeviceMessages.createActiveDisplaysResponse(payload(msg).getSequence(), ids);
                ctx.getSender().send(response);
            }
        });

        register(DaemonControlMessages.TYPE_INJECT_INPUT_EVENT_WITH_DISPLAY_ID, ExecutionPolicy.FAST, (msg, ctx) -> {
            DaemonControlMessage dto = payload(msg);
            Parcel parcel = Parcel.obtain();
            parcel.unmarshall(dto.getData(), 0, dto.getData().length);
            parcel.setDataPosition(0);
            InputEvent event;
            if (dto.isKeyEvent()) {
                event = KeyEvent.CREATOR.createFromParcel(parcel);
            } else {
                event = MotionEvent.CREATOR.createFromParcel(parcel);
            }
            parcel.recycle();

            int targetDisplayId = dto.getDisplayId();
            boolean ok = Device.injectEvent(event, targetDisplayId, Device.INJECT_MODE_ASYNC);

            Ln.d("handleInjectInputEvent: displayId=" + targetDisplayId
                    + ", isKey=" + dto.isKeyEvent()
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
            DaemonControlMessage dto = payload(msg);
            int displayId = dto.getDisplayId();
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
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Ln.d("handleExitDaemon: delay interrupted, proceeding to exit");
                }
                exitCoordinator.requestExit();
                close();
            }).start();
        });

        register(DaemonControlMessages.TYPE_START_VIDEO_STREAM, ExecutionPolicy.SLOW, (msg, ctx) -> {
            if (ctx.getVideoController() == null) {
                throw new RuntimeException("Video controller not available");
            }
            DaemonControlMessage dto = payload(msg);
            int displayId = dto.getDisplayId();
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

        register(DaemonControlMessages.TYPE_GET_ROTATION, ExecutionPolicy.FAST, (msg, ctx) -> {
            DaemonControlMessage dto = payload(msg);
            int rotation = rotationController.getRotation(dto.getDisplayId());
            sendSuccessResponse(msg, dto.getDisplayId(), String.valueOf(rotation));
        });

        register(DaemonControlMessages.TYPE_FREEZE_ROTATION, ExecutionPolicy.FAST, (msg, ctx) -> {
            DaemonControlMessage dto = payload(msg);
            // rotation (0-3) carried in dto.getFlags() (see DaemonControlMessages).
            rotationController.freeze(dto.getDisplayId(), dto.getFlags());
            sendSuccessResponse(msg, dto.getDisplayId(), "OK");
        });

        register(DaemonControlMessages.TYPE_THAW_ROTATION, ExecutionPolicy.FAST, (msg, ctx) -> {
            DaemonControlMessage dto = payload(msg);
            rotationController.thaw(dto.getDisplayId());
            sendSuccessResponse(msg, dto.getDisplayId(), "OK");
        });

        register(DaemonControlMessages.TYPE_IS_ROTATION_FROZEN, ExecutionPolicy.FAST, (msg, ctx) -> {
            DaemonControlMessage dto = payload(msg);
            int frozen = rotationController.isFrozen(dto.getDisplayId());
            sendSuccessResponse(msg, dto.getDisplayId(), String.valueOf(frozen));
        });

        register(DaemonControlMessages.TYPE_GET_ACTIVE_DISPLAY_INFOS, ExecutionPolicy.FAST, (msg, ctx) -> {
            com.genymobile.scrcpy.display.DisplayInfo[] infos = registry.getActiveDisplayInfos();
            if (ctx.getSender() != null) {
                DeviceMessage response = DaemonDeviceMessages.createActiveDisplayInfosResponse(payload(msg).getSequence(), infos);
                ctx.getSender().send(response);
            }
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
                DeviceMessage response = DaemonDeviceMessages.createGenericResponse(payload(msg).getSequence(), 0, extraData, text);
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
                DeviceMessage response = DaemonDeviceMessages.createGenericResponse(payload(msg).getSequence(), -1, -1, errorMsg);
                sender.send(response);
            } catch (Exception e) {
                Ln.e("Failed to send error response", e);
            }
        }
    }
}
