package com.gathin.tvhome;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.UserManager;

/**
 * The Home entry that makes this launcher win Home on its own, with nothing set in Settings: it declares HOME with
 * priority 1000 and is directBootAware (cleantv-fork's MainActivity has both). The priority beats a stock launcher
 * that has none, and directBootAware lets it be chosen at boot, before the user is unlocked, as the stock launcher
 * is; a launcher that is not aware is left out of that choice.
 *
 * <p>MainActivity itself is not directBootAware: it reads credential-protected storage (preferences, the theme
 * folder) all through onCreate, which fails before unlock. So this activity only shows a plain screen, waits for
 * the user to be unlocked (at once on a TV with no lock), then starts MainActivity with the HOME intent and finishes.
 * Android's own FallbackHome does the same. Nothing here reads preferences or files.
 */
public class HomeEntryActivity extends Activity {
    private BroadcastReceiver unlocked;
    private boolean forwarded;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (isUserUnlocked()) {
            forward();
            return;
        }
        // Still in direct boot: wait for the unlock, then go on.
        unlocked = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                forward();
            }
        };
        registerReceiver(unlocked, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        if (isUserUnlocked()) forward();   // unlocked between the check and the registration
    }

    private boolean isUserUnlocked() {
        UserManager users = getSystemService(UserManager.class);
        return users == null || users.isUserUnlocked();
    }

    /** Starts MainActivity as Home (the same HOME intent the system used, so its onNewIntent sees CATEGORY_HOME). */
    private void forward() {
        if (forwarded) return;
        forwarded = true;
        Intent home = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setClass(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(home);
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        if (unlocked != null) {
            try {
                unregisterReceiver(unlocked);
            } catch (RuntimeException ignored) {
                // already gone
            }
            unlocked = null;
        }
        super.onDestroy();
    }
}
