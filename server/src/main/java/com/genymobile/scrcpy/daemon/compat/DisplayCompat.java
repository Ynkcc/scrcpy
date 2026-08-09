package com.genymobile.scrcpy.daemon.compat;

import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.wrappers.ServiceManager;

import android.os.IBinder;
import android.os.IInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DisplayCompat {

    private static Method getServiceMethod;
    private static Method asInterfaceMethod;
    private static Method getRecentTasksMethod;
    private static Method getListMethod;
    private static Field displayIdField;
    private static Field taskIdField;
    private static Method moveRootTaskToDisplayMethod;
    private static Method moveStackToDisplayMethod;
    private static IInterface atmInstance;

    private static Method cachedOrphanReleaseMethod;
    private static Object cachedOrphanReleaseTarget;
    private static boolean orphanReleaseSearched = false;

    static {
        try {
            Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
            getServiceMethod = serviceManagerClass.getMethod("getService", String.class);
            IBinder binder = (IBinder) getServiceMethod.invoke(null, "activity_task");

            Class<?> atmStubClass = Class.forName("android.app.IActivityTaskManager$Stub");
            asInterfaceMethod = atmStubClass.getMethod("asInterface", IBinder.class);
            atmInstance = (IInterface) asInterfaceMethod.invoke(null, binder);

            if (atmInstance != null) {
                Class<?> atmClass = atmInstance.getClass();
                getRecentTasksMethod = atmClass.getMethod("getRecentTasks", int.class, int.class, int.class);

                try {
                    moveRootTaskToDisplayMethod = atmClass.getMethod("moveRootTaskToDisplay", int.class, int.class);
                } catch (NoSuchMethodException e) {
                    try {
                        moveStackToDisplayMethod = atmClass.getMethod("moveStackToDisplay", int.class, int.class);
                    } catch (NoSuchMethodException ex) {
                        Ln.w("DisplayCompat: Neither moveRootTaskToDisplay nor moveStackToDisplay found");
                    }
                }
            }

            Class<?> taskInfoClass = Class.forName("android.app.ActivityManager$RecentTaskInfo");
            displayIdField = taskInfoClass.getField("displayId");
            taskIdField = taskInfoClass.getField("taskId");

        } catch (Throwable t) {
            Ln.e("DisplayCompat: Failed to initialize task migration reflection", t);
        }
    }

    private DisplayCompat() {
    }

    public static void moveTasksToDefaultDisplay(int displayId) {
        try {
            Ln.i("DisplayCompat: Checking tasks on display " + displayId + " to move back to display 0");
            if (atmInstance == null || getRecentTasksMethod == null) {
                Ln.w("DisplayCompat: atmInstance or getRecentTasksMethod is null, skip migration");
                return;
            }

            Object parceledList = getRecentTasksMethod.invoke(atmInstance, 50, 0, -2);
            if (parceledList == null) {
                return;
            }

            if (getListMethod == null) {
                getListMethod = parceledList.getClass().getMethod("getList");
            }
            List<?> list = (List<?>) getListMethod.invoke(parceledList);

            if (list != null && displayIdField != null && taskIdField != null) {
                for (Object taskInfo : list) {
                    int taskDisplayId = displayIdField.getInt(taskInfo);
                    int taskId = taskIdField.getInt(taskInfo);

                    if (taskDisplayId == displayId) {
                        Ln.i("DisplayCompat: Moving task " + taskId + " back to display 0");
                        if (moveRootTaskToDisplayMethod != null) {
                            moveRootTaskToDisplayMethod.invoke(atmInstance, taskId, 0);
                        } else if (moveStackToDisplayMethod != null) {
                            moveStackToDisplayMethod.invoke(atmInstance, taskId, 0);
                        } else {
                            Ln.w("DisplayCompat: Move task method not available");
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Ln.e("DisplayCompat: Failed to move tasks back to display 0", t);
        }
    }

    public static boolean bestEffortReleaseOrphan(int displayId) {
        synchronized (DisplayCompat.class) {
            if (orphanReleaseSearched && cachedOrphanReleaseMethod != null && cachedOrphanReleaseTarget != null) {
                try {
                    cachedOrphanReleaseMethod.invoke(cachedOrphanReleaseTarget, displayId);
                    Ln.i("DisplayCompat: bestEffortReleaseOrphan succeeded via cached method=" + cachedOrphanReleaseMethod.getName());
                    return true;
                } catch (Throwable t) {
                    Ln.w("DisplayCompat: cached release method failed, re-scanning...", t);
                    cachedOrphanReleaseMethod = null;
                    cachedOrphanReleaseTarget = null;
                    orphanReleaseSearched = false;
                }
            }
        }

        try {
            com.genymobile.scrcpy.wrappers.DisplayManager scrcpyDm = ServiceManager.getDisplayManager();
            Field managerField = scrcpyDm.getClass().getDeclaredField("manager");
            managerField.setAccessible(true);
            Object dmg = managerField.get(scrcpyDm);
            if (dmg == null) {
                return false;
            }

            List<Object> targets = new ArrayList<>();
            targets.add(dmg);
            try {
                Field mDmField = dmg.getClass().getDeclaredField("mDm");
                mDmField.setAccessible(true);
                Object mDm = mDmField.get(dmg);
                if (mDm != null) {
                    targets.add(mDm);
                }
            } catch (Throwable ignored) {
            }

            for (Object target : targets) {
                for (Method method : target.getClass().getDeclaredMethods()) {
                    Class<?>[] params = method.getParameterTypes();
                    if (params.length == 1 && (params[0] == int.class || params[0] == Integer.class)) {
                        String name = method.getName().toLowerCase(Locale.ROOT);
                        if (name.contains("release") || name.contains("remove")) {
                            try {
                                method.setAccessible(true);
                                method.invoke(target, displayId);
                                Ln.w("DisplayCompat: bestEffortReleaseOrphan succeeded via method=" + method.getName() + " on " + target.getClass().getName());
                                synchronized (DisplayCompat.class) {
                                    cachedOrphanReleaseMethod = method;
                                    cachedOrphanReleaseTarget = target;
                                    orphanReleaseSearched = true;
                                }
                                return true;
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Ln.e("DisplayCompat: bestEffortReleaseOrphan failed", t);
        }

        synchronized (DisplayCompat.class) {
            orphanReleaseSearched = true;
        }
        return false;
    }
}
