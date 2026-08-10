package com.genymobile.scrcpy.wrappers;

import com.genymobile.scrcpy.AndroidVersions;
import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.display.DisplayInfo;
import com.genymobile.scrcpy.model.Size;
import com.genymobile.scrcpy.util.Command;
import com.genymobile.scrcpy.util.Ln;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.view.Display;
import android.view.Surface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressLint("PrivateApi,DiscouragedPrivateApi")
public final class DisplayManager {

    // android.hardware.display.DisplayManager.EVENT_FLAG_DISPLAY_CHANGED
    public static final long EVENT_FLAG_DISPLAY_CHANGED = 1L << 2;

    public interface DisplayListener {
        /**
         * Called whenever the properties of a logical {@link android.view.Display},
         * such as size and density, have changed.
         *
         * @param displayId The id of the logical display that changed.
         */
        void onDisplayChanged(int displayId);
    }

    public static final class DisplayListenerHandle {
        private final Object displayListenerProxy;
        private DisplayListenerHandle(Object displayListenerProxy) {
            this.displayListenerProxy = displayListenerProxy;
        }
    }

    private final Object manager; // instance of hidden class android.hardware.display.DisplayManagerGlobal
    private Method getDisplayInfoMethod;
    private Method createVirtualDisplayMethod;
    private Method createVirtualDisplayGlobalMethod;
    private Constructor<?> displayManagerCtor;
    private Method requestDisplayPowerMethod;
    private boolean requestDisplayPowerUsesInt; // true 表示第二个参数为 int（Display.STATE_*），false 表示 boolean

    static DisplayManager create() {
        try {
            Class<?> clazz = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Method getInstanceMethod = clazz.getDeclaredMethod("getInstance");
            Object dmg = getInstanceMethod.invoke(null);
            return new DisplayManager(dmg);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private DisplayManager(Object manager) {
        this.manager = manager;
    }

    // public to call it from unit tests
    public static DisplayInfo parseDisplayInfo(String dumpsysDisplayOutput, int displayId) {
        Pattern regex = Pattern.compile(
                "^    mOverrideDisplayInfo=DisplayInfo\\{\".*?, displayId " + displayId + ".*?(, FLAG_.*)?, real ([0-9]+) x ([0-9]+).*?, "
                        + "rotation ([0-9]+).*?, density ([0-9]+).*?, layerStack ([0-9]+)",
                Pattern.MULTILINE);
        Matcher m = regex.matcher(dumpsysDisplayOutput);
        if (!m.find()) {
            return null;
        }
        int flags = parseDisplayFlags(m.group(1));
        int width = Integer.parseInt(m.group(2));
        int height = Integer.parseInt(m.group(3));
        int rotation = Integer.parseInt(m.group(4));
        int density = Integer.parseInt(m.group(5));
        int layerStack = Integer.parseInt(m.group(6));

        return new DisplayInfo(displayId, new Size(width, height), rotation, layerStack, flags, density, null);
    }

    private static DisplayInfo getDisplayInfoFromDumpsysDisplay(int displayId) {
        try {
            String dumpsysDisplayOutput = Command.execReadOutput("dumpsys", "display");
            return parseDisplayInfo(dumpsysDisplayOutput, displayId);
        } catch (Exception e) {
            Ln.e("Could not get display info from \"dumpsys display\" output", e);
            return null;
        }
    }

    private static int parseDisplayFlags(String text) {
        if (text == null) {
            return 0;
        }

        int flags = 0;
        Pattern regex = Pattern.compile("FLAG_[A-Z_]+");
        Matcher m = regex.matcher(text);
        while (m.find()) {
            String flagString = m.group();
            try {
                Field filed = Display.class.getDeclaredField(flagString);
                flags |= filed.getInt(null);
            } catch (ReflectiveOperationException e) {
                // Silently ignore, some flags reported by "dumpsys display" are @TestApi
            }
        }
        return flags;
    }

    // getDisplayInfo() may be used from both the Controller thread and the video (main) thread
    private synchronized Method getGetDisplayInfoMethod() throws NoSuchMethodException {
        if (getDisplayInfoMethod == null) {
            getDisplayInfoMethod = manager.getClass().getMethod("getDisplayInfo", int.class);
        }
        return getDisplayInfoMethod;
    }

    public DisplayInfo getDisplayInfo(int displayId) {
        try {
            Method method = getGetDisplayInfoMethod();
            Object displayInfo = method.invoke(manager, displayId);
            if (displayInfo == null) {
                // fallback when displayInfo is null
                return getDisplayInfoFromDumpsysDisplay(displayId);
            }
            Class<?> cls = displayInfo.getClass();
            // width and height already take the rotation into account
            int width = cls.getDeclaredField("logicalWidth").getInt(displayInfo);
            int height = cls.getDeclaredField("logicalHeight").getInt(displayInfo);
            int rotation = cls.getDeclaredField("rotation").getInt(displayInfo);
            int layerStack = cls.getDeclaredField("layerStack").getInt(displayInfo);
            int flags = cls.getDeclaredField("flags").getInt(displayInfo);
            int dpi = cls.getDeclaredField("logicalDensityDpi").getInt(displayInfo);
            String uniqueId;
            try {
                uniqueId = (String) cls.getDeclaredField("uniqueId").get(displayInfo);
            } catch (NoSuchFieldException e) {
                // This field might not exist: <https://github.com/Genymobile/scrcpy/issues/6461>
                uniqueId = null;
            }
            return new DisplayInfo(displayId, new Size(width, height), rotation, layerStack, flags, dpi, uniqueId);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    public int[] getDisplayIds() {
        try {
            return (int[]) manager.getClass().getMethod("getDisplayIds").invoke(manager);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private Method getCreateVirtualDisplayMethod() throws NoSuchMethodException {
        if (createVirtualDisplayMethod == null) {
            createVirtualDisplayMethod = android.hardware.display.DisplayManager.class
                    .getMethod("createVirtualDisplay", String.class, int.class, int.class, int.class, Surface.class);
        }
        return createVirtualDisplayMethod;
    }

    public VirtualDisplay createVirtualDisplay(String name, int width, int height, int displayIdToMirror, Surface surface) throws Exception {
        Method method = getCreateVirtualDisplayMethod();
        return (VirtualDisplay) method.invoke(null, name, width, height, displayIdToMirror, surface);
    }

    public VirtualDisplay createNewVirtualDisplay(String name, int width, int height, int dpi, Surface surface, int flags) throws Exception {
        // Prefer DisplayManagerGlobal.createVirtualDisplay over constructing a new
        // DisplayManager(Context): the DisplayManagerGlobal singleton is already
        // fully initialized, and its createVirtualDisplay method has been stable
        // across Android versions (with a few overloads). The previous approach of
        // reflectively constructing DisplayManager with FakeContext is brittle —
        // FakeContext wraps Workarounds.getSystemContext() and on many Android
        // builds the shell/root UID cannot satisfy DisplayManager's internal
        // invariants, causing InvocationTargetException inside
        // dm.createVirtualDisplay (e.g. "Invalid display manager service handle").
        if (createVirtualDisplayGlobalMethod == null) {
            // Probe several common overload signatures of DisplayManagerGlobal.
            // The full signature on recent Android is:
            //   createVirtualDisplay(String pkgName, String name, int w, int h,
            //       int dpi, Surface surface, int flags, VirtualDisplay.Callback cb,
            //       Handler handler, String uniqueId)
            // Older versions have fewer params.
            Class<?>[] pkg = {String.class, String.class, int.class, int.class, int.class, Surface.class, int.class};
            Class<?>[] cb = {String.class, String.class, int.class, int.class, int.class, Surface.class, int.class,
                    android.hardware.display.VirtualDisplay.Callback.class, android.os.Handler.class};
            Class<?>[] full = {String.class, String.class, int.class, int.class, int.class, Surface.class, int.class,
                    android.hardware.display.VirtualDisplay.Callback.class, android.os.Handler.class, String.class};
            Class<?>[] noPkg = {String.class, int.class, int.class, int.class, Surface.class, int.class};
            Class<?>[][] candidates = {full, cb, pkg, noPkg};
            Method chosen = null;
            for (Class<?>[] c : candidates) {
                try {
                    chosen = manager.getClass().getDeclaredMethod("createVirtualDisplay", c);
                    chosen.setAccessible(true);
                    break;
                } catch (NoSuchMethodException ignored) {
                    // try next
                }
            }
            createVirtualDisplayGlobalMethod = chosen;
        }

        if (createVirtualDisplayGlobalMethod != null) {
            Method m = createVirtualDisplayGlobalMethod;
            Class<?>[] ptypes = m.getParameterTypes();
            Object[] args = new Object[ptypes.length];
            int idx = 0;
            if (ptypes[idx] == String.class && ptypes.length > 1 && ptypes[idx + 1] == String.class) {
                // first param is packageName
                args[idx++] = FakeContext.PACKAGE_NAME;
            }
            args[idx++] = name;
            args[idx++] = width;
            args[idx++] = height;
            args[idx++] = dpi;
            args[idx++] = surface;
            if (idx < ptypes.length && ptypes[idx] == int.class) {
                args[idx++] = flags;
            }
            // Callback + Handler + uniqueId: leave null, they are optional
            while (idx < args.length) {
                args[idx++] = null;
            }
            try {
                VirtualDisplay vd = (VirtualDisplay) m.invoke(manager, args);
                if (vd != null) {
                    Ln.i("DisplayManager: created VD via Global (method arity=" + ptypes.length + ")");
                    return vd;
                }
            } catch (java.lang.reflect.InvocationTargetException ite) {
                Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
                Ln.w("DisplayManager: Global.createVirtualDisplay failed, will fallback: " + cause);
                // Fall through to fallback path below
            }
        }

        // Fallback: construct DisplayManager via Context (old path).
        // Cache the constructor to avoid repeated reflective lookup.
        if (displayManagerCtor == null) {
            displayManagerCtor = android.hardware.display.DisplayManager.class.getDeclaredConstructor(Context.class);
            displayManagerCtor.setAccessible(true);
        }
        android.hardware.display.DisplayManager dm =
                (android.hardware.display.DisplayManager) displayManagerCtor.newInstance(FakeContext.get());
        Ln.w("DisplayManager: falling back to DisplayManager(Context) path");
        return dm.createVirtualDisplay(name, width, height, dpi, surface, flags);
    }

    private Method getRequestDisplayPowerMethod() throws NoSuchMethodException {
        if (requestDisplayPowerMethod == null) {
            // 不同 Android 版本第二个参数不同：boolean（旧）或 int Display.STATE_*（新）
            try {
                requestDisplayPowerMethod = manager.getClass().getDeclaredMethod("requestDisplayPower", int.class, boolean.class);
                requestDisplayPowerUsesInt = false;
            } catch (NoSuchMethodException e) {
                requestDisplayPowerMethod = manager.getClass().getDeclaredMethod("requestDisplayPower", int.class, int.class);
                requestDisplayPowerUsesInt = true;
            }
            requestDisplayPowerMethod.setAccessible(true);
        }
        return requestDisplayPowerMethod;
    }

    @TargetApi(AndroidVersions.API_35_ANDROID_15)
    public boolean requestDisplayPower(int displayId, boolean on) {
        if (android.os.Build.VERSION.SDK_INT < AndroidVersions.API_35_ANDROID_15) {
            return false;
        }
        try {
            Method method = getRequestDisplayPowerMethod();
            if (requestDisplayPowerUsesInt) {
                // int 版本使用 Display.STATE_ON(2) / Display.STATE_OFF(1)
                int state = on ? android.view.Display.STATE_ON : android.view.Display.STATE_OFF;
                return (boolean) method.invoke(manager, displayId, state);
            }
            return (boolean) method.invoke(manager, displayId, on);
        } catch (ReflectiveOperationException e) {
            Ln.e("Could not invoke method", e);
            return false;
        }
    }

    public DisplayListenerHandle registerDisplayListener(DisplayListener listener, Handler handler) {
        try {
            Class<?> displayListenerClass = Class.forName("android.hardware.display.DisplayManager$DisplayListener");
            Object displayListenerProxy = Proxy.newProxyInstance(
                    ClassLoader.getSystemClassLoader(),
                    new Class[] {displayListenerClass},
                    (proxy, method, args) -> {
                        if ("onDisplayChanged".equals(method.getName())) {
                            listener.onDisplayChanged((int) args[0]);
                        }
                        if ("toString".equals(method.getName())) {
                            return "DisplayListener";
                        }
                        return null;
                    });
            try {
                manager.getClass()
                        .getMethod("registerDisplayListener", displayListenerClass, Handler.class, long.class, String.class)
                        .invoke(manager, displayListenerProxy, handler, EVENT_FLAG_DISPLAY_CHANGED, FakeContext.PACKAGE_NAME);
            } catch (NoSuchMethodException e) {
                try {
                    manager.getClass()
                            .getMethod("registerDisplayListener", displayListenerClass, Handler.class, long.class)
                            .invoke(manager, displayListenerProxy, handler, EVENT_FLAG_DISPLAY_CHANGED);
                } catch (NoSuchMethodException e2) {
                    manager.getClass()
                            .getMethod("registerDisplayListener", displayListenerClass, Handler.class)
                            .invoke(manager, displayListenerProxy, handler);
                }
            }

            return new DisplayListenerHandle(displayListenerProxy);
        } catch (Exception e) {
            // Rotation and screen size won't be updated, not a fatal error
            Ln.e("Could not register display listener", e);
        }

        return null;
    }

    public void unregisterDisplayListener(DisplayListenerHandle listener) {
        try {
            Class<?> displayListenerClass = Class.forName("android.hardware.display.DisplayManager$DisplayListener");
            manager.getClass().getMethod("unregisterDisplayListener", displayListenerClass).invoke(manager, listener.displayListenerProxy);
        } catch (Exception e) {
            Ln.e("Could not unregister display listener", e);
        }
    }
}
