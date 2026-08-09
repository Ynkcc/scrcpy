package com.genymobile.scrcpy.daemon.display;

import com.genymobile.scrcpy.AndroidVersions;
import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.wrappers.ServiceManager;

import android.app.ActivityOptions;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

public final class ActivityLauncher {

    private ActivityLauncher() {
    }

    public static int startActivity(String packageName, int displayId) {
        try {
            Ln.i("ActivityLauncher: startActivity for package=" + packageName + " on display " + displayId);
            PackageManager pm = FakeContext.get().getPackageManager();
            Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
            if (launchIntent == null) {
                launchIntent = pm.getLeanbackLaunchIntentForPackage(packageName);
            }
            if (launchIntent == null) {
                Ln.w("ActivityLauncher: Cannot create launch intent for app " + packageName);
                return -1;
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            Bundle options = null;
            if (Build.VERSION.SDK_INT >= AndroidVersions.API_26_ANDROID_8_0) {
                ActivityOptions launchOptions = ActivityOptions.makeBasic();
                launchOptions.setLaunchDisplayId(displayId);
                options = launchOptions.toBundle();
            }

            int result = ServiceManager.getActivityManager().startActivity(launchIntent, options);
            Ln.i("ActivityLauncher: startActivity result=" + result);
            return result >= 0 ? 0 : -1;
        } catch (Exception e) {
            Ln.e("ActivityLauncher: failed to start activity", e);
            return -1;
        }
    }
}
