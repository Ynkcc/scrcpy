package com.genymobile.scrcpy.daemon.display;

import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.model.DeviceApp;
import com.genymobile.scrcpy.util.Ln;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AppLister {

    private AppLister() {
    }

    /**
     * List installed apps on the device that have a launch intent.
     *
     * <p>Returns a snapshot sorted by package name. Filters out pure system
     * packages (no launcher intent) so the remote AppSelectionDialog matches
     * what a local launcher would show.
     */
    public static List<DeviceApp> listInstalledApps() {
        try {
            PackageManager pm = FakeContext.get().getPackageManager();
            List<ApplicationInfo> all = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            List<DeviceApp> result = new ArrayList<>(all.size());
            for (ApplicationInfo info : all) {
                // Filter: keep non-system apps, or system apps that expose a
                // launch intent (e.g. Settings).
                boolean isSystem = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                Intent launchIntent = pm.getLaunchIntentForPackage(info.packageName);
                if (launchIntent == null) {
                    launchIntent = pm.getLeanbackLaunchIntentForPackage(info.packageName);
                }
                if (launchIntent == null) {
                    continue;
                }
                CharSequence label = info.loadLabel(pm);
                result.add(new DeviceApp(info.packageName, label != null ? label.toString() : info.packageName, isSystem));
            }
            Collections.sort(result, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            return result;
        } catch (Exception e) {
            Ln.e("AppLister: failed to list installed apps", e);
            return Collections.emptyList();
        }
    }
}
