package com.gathin.tvhome;

import android.Manifest;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Taking over from the stock launcher without ADB. build-and-install does it with shell commands (disable the stock
 * launcher, set the Home activity); an app cannot do either by itself, so this does what an app is allowed to:
 * ask Android to make this launcher the default Home (the system's own dialog or Home picker), and, through
 * HomeKeyService, send the TV back here whenever a stock launcher's home screen comes up (at boot, or on Home).
 */
final class HomeTakeover {
    /** Home activities that are not launchers: the setup wizard and recovery must be left to run. */
    private static final Pattern NOT_A_LAUNCHER = Pattern.compile("setup|wizard|provision|recovery|oobe|factory|fallback",
            Pattern.CASE_INSENSITIVE);

    /** Request code of the role dialog: its answer is not needed, but it must be started for a result. */
    static final int REQUEST_HOME_ROLE = 43;

    private HomeTakeover() {
    }

    /** True for a system app that handles Home and looks like a launcher (not the setup wizard), other than {@code own}. */
    static boolean isStockCandidate(String packageName, boolean systemApp, String own) {
        if (packageName == null || packageName.isEmpty() || packageName.equals(own) || "android".equals(packageName)) return false;
        return systemApp && !NOT_A_LAUNCHER.matcher(packageName).find();
    }

    /** The stock launchers installed on this TV, disabled ones included (firmware can turn them back on). */
    static Set<String> stockLauncherPackages(Context ctx) {
        Set<String> found = new LinkedHashSet<>();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : ctx.getPackageManager().queryIntentActivities(home, PackageManager.MATCH_DISABLED_COMPONENTS)) {
            ApplicationInfo app = ri.activityInfo.applicationInfo;
            boolean system = app != null && (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            if (isStockCandidate(ri.activityInfo.packageName, system, ctx.getPackageName())) found.add(ri.activityInfo.packageName);
        }
        return found;
    }

    /** True when Home currently opens this launcher without asking. */
    static boolean isDefaultHome(Context ctx) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = ctx.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && ctx.getPackageName().equals(ri.activityInfo.packageName);
    }

    /**
     * Asks Android to make this launcher the default Home: the system's role dialog on Android 10 and later when the
     * TV provides it, otherwise the Home picker (HomeSettingsActivity), otherwise a message that the TV has neither.
     */
    static void requestDefaultHome(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager roles = activity.getSystemService(RoleManager.class);
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
                if (roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                    Toast.makeText(activity, R.string.default_home_already, Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    activity.startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), REQUEST_HOME_ROLE);
                    return;
                } catch (ActivityNotFoundException | SecurityException ignored) {
                    // this TV has no dialog for the role: use the Home picker below
                }
            }
        }
        try {
            activity.startActivity(new Intent(activity, HomeSettingsActivity.class));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(activity, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }

    /** True when {@code list} (Settings.Secure enabled_accessibility_services) names the service {@code pkg}/{@code cls}. */
    static boolean listHas(String list, String pkg, String cls) {
        if (list == null) return false;
        for (String entry : list.split(":")) {
            int slash = entry.indexOf('/');
            if (slash <= 0) continue;
            String p = entry.substring(0, slash);
            String c = entry.substring(slash + 1);
            if (c.startsWith(".")) c = p + c;
            if (p.equals(pkg) && c.equals(cls)) return true;
        }
        return false;
    }

    /** {@code list} with the service added (in the short form Android writes), unchanged when it is already there. */
    static String listWith(String list, String pkg, String cls) {
        if (listHas(list, pkg, cls)) return list;
        String entry = pkg + "/" + (cls.startsWith(pkg + ".") ? cls.substring(pkg.length()) : cls);
        return list == null || list.isEmpty() || "null".equals(list) ? entry : list + ":" + entry;
    }

    /** True when the accessibility service {@code service} of this app is switched on. */
    static boolean serviceEnabled(Context ctx, Class<?> service) {
        ContentResolver cr = ctx.getContentResolver();
        try {
            return Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
                    && listHas(Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                    ctx.getPackageName(), service.getName());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Switches the service on directly, which Android allows only when WRITE_SECURE_SETTINGS was granted to this app
     * (a TV that once had ADB can have it). Returns whether the service is on afterwards.
     */
    static boolean enableService(Context ctx, Class<?> service) {
        if (serviceEnabled(ctx, service)) return true;
        if (ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return false;
        try {
            ContentResolver cr = ctx.getContentResolver();
            String list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    listWith(list, ctx.getPackageName(), service.getName()));
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            return serviceEnabled(ctx, service);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Opens the system's accessibility settings, where the owner switches the helper on by hand. */
    static void openAccessibilitySettings(Activity activity) {
        try {
            activity.startActivity(SystemSettings.intent(activity, Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(activity, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
