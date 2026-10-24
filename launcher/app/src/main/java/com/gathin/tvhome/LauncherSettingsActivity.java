package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    static final String EXTRA_REORDER = "com.gathin.tvhome.extra.REORDER_FROM_SETTINGS";
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private static final int[] DOCK_OPACITIES = {100, 70, 50, 30};
    private final TextView[] dockChoices = new TextView[DOCK_OPACITIES.length];
    private TextView wallpaperChoice;
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private TextView dockOnlyChoice;
    private TextView lunarChoice;
    private TextView defaultHomeChoice;
    private TextView stockRedirectChoice;
    private TextView dockDownLabel;
    private TextView heroTitleLabel;
    private SeekBar dockDownSlider;
    private SeekBar heroTitleSlider;
    private static final int PICK_IMAGE_REQUEST = 41;
    private static final int PICK_THEME_REQUEST = 42;
    private int columns;
    private int rowsBelowDock;
    private int dockOpacity;
    private boolean animations;
    private boolean advancedEffects;
    private boolean dockOnly;
    private boolean lunar;
    private boolean stockRedirect;
    private int dockDown;   // dp the dock is lowered by
    private int heroTitleDelta;   // sp larger (+) or smaller (-) than the theme's size

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        View reorderChoice = findViewById(R.id.reorderChoice);
        reorderChoice.setVisibility(config.launcherSettingsButton ? View.VISIBLE : View.GONE);
        reorderChoice.setOnClickListener(v -> {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_REORDER, true));
            finish();
        });
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        dockOpacity = config.dockOpacity;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                changed();
                refresh();
            });
        }
        int[] rowIds = {R.id.rows0, R.id.rows1, R.id.rows2, R.id.rows3};
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i;
            rowChoices[i] = findViewById(rowIds[i]);
            rowChoices[i].setOnClickListener(v -> {
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value).apply();
                changed();
                refresh();
            });
        }
        int[] dockIds = {R.id.dock100, R.id.dock70, R.id.dock50, R.id.dock30};
        for (int i = 0; i < dockIds.length; i++) {
            final int value = DOCK_OPACITIES[i];
            dockChoices[i] = findViewById(dockIds[i]);
            dockChoices[i].setOnClickListener(v -> {
                if (dockOpacity == value) return;
                dockOpacity = value;
                choices.edit().putInt(Config.DOCK_OPACITY_KEY, value).apply();
                changed();
                refresh();
            });
        }
        dockOnly = HomeChoices.dockOnly(this);
        lunar = HomeChoices.lunarDate(this);
        dockDown = HomeChoices.dockDownDp(this);
        heroTitleDelta = HomeChoices.heroTitleDeltaSp(this);
        stockRedirect = HomeChoices.stockRedirect(this);
        // The default-home row that was already here: it now tries Android's role dialog first (HomeTakeover) and falls
        // back to the Home picker with the same help text as before, and shows whether this launcher is the default.
        defaultHomeChoice = findViewById(R.id.defaultHomeChoice);
        defaultHomeChoice.setOnClickListener(v -> HomeTakeover.requestDefaultHome(this));
        stockRedirectChoice = findViewById(R.id.stockRedirectChoice);
        stockRedirectChoice.setOnClickListener(v -> {
            stockRedirect = !stockRedirect;
            choices.edit().putBoolean(HomeChoices.STOCK_REDIRECT_KEY, stockRedirect).apply();
            if (stockRedirect && !HomeTakeover.serviceEnabled(this, HomeKeyService.class)) {
                if (HomeTakeover.enableService(this, HomeKeyService.class)) {
                    Toast.makeText(this, R.string.stock_redirect_enabled, Toast.LENGTH_SHORT).show();
                } else {
                    // Android lets only the owner switch an accessibility service on: open its list and say which one
                    Toast.makeText(this, getString(R.string.stock_redirect_enable_hint, getString(R.string.home_key_name)),
                            Toast.LENGTH_LONG).show();
                    HomeTakeover.openAccessibilitySettings(this);
                }
            }
            refresh();
        });
        dockOnlyChoice = findViewById(R.id.dockOnlyChoice);
        dockOnlyChoice.setOnClickListener(v -> {
            dockOnly = !dockOnly;
            choices.edit().putBoolean(HomeChoices.DOCK_ONLY_KEY, dockOnly).apply();
            changed();
            refresh();
        });
        lunarChoice = findViewById(R.id.lunarChoice);
        lunarChoice.setOnClickListener(v -> {
            lunar = !lunar;
            choices.edit().putBoolean(HomeChoices.LUNAR_KEY, lunar).apply();
            changed();
            refresh();
        });
        dockDownLabel = findViewById(R.id.dockDownLabel);
        dockDownSlider = findViewById(R.id.dockDownSlider);
        dockDownSlider.setMax(HomeChoices.DOCK_DOWN_MAX_DP / HomeChoices.DOCK_DOWN_STEP_DP);
        dockDownSlider.setKeyProgressIncrement(1);
        dockDownSlider.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            void onUserChange(int progress) {
                dockDown = progress * HomeChoices.DOCK_DOWN_STEP_DP;
                choices.edit().putInt(HomeChoices.DOCK_DOWN_KEY, dockDown).apply();
                changed();
                refresh();
            }
        });
        heroTitleLabel = findViewById(R.id.heroTitleLabel);
        heroTitleSlider = findViewById(R.id.heroTitleSlider);
        heroTitleSlider.setMax(2 * HomeChoices.HERO_TITLE_RANGE_SP / HomeChoices.HERO_TITLE_STEP_SP);
        heroTitleSlider.setKeyProgressIncrement(1);
        heroTitleSlider.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            void onUserChange(int progress) {
                heroTitleDelta = HomeChoices.heroTitleDeltaFromProgress(progress);
                choices.edit().putInt(HomeChoices.HERO_TITLE_KEY, heroTitleDelta).apply();
                changed();
                refresh();
            }
        });
        // Keep the numeric choices increasing in the Hebrew reading direction. Reorder the
        // actual views so D-pad focus follows their positions without changing the saved values.
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);
            reverseRow(dockChoices);
        }
        findViewById(R.id.importThemeChoice).setOnClickListener(v -> pickThemeFile());
        findViewById(R.id.receiveThemeChoice).setOnClickListener(v ->
                startActivity(new Intent(this, ThemeReceiveActivity.class)));
        wallpaperChoice = findViewById(R.id.wallpaperChoice);
        wallpaperChoice.setOnClickListener(v -> showWallpaperPicker());
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            changed();
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            HomeChoices.reset(choices.edit()
                    .remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                    .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY)
                    .remove(Config.DOCK_OPACITY_KEY)).commit();
            dockOnly = HomeChoices.dockOnly(this);
            lunar = HomeChoices.lunarDate(this);
            dockDown = HomeChoices.dockDownDp(this);
            heroTitleDelta = HomeChoices.heroTitleDeltaSp(this);
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            dockOpacity = current.dockOpacity;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            changed();
            refresh();
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));
        refresh();
        if (config.launcherSettingsButton) reorderChoice.requestFocus();
        else columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }

    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    private void showWallpaperPicker() {
        File dir = Config.dir(this);
        List<File> images = new java.util.ArrayList<>(WallpaperRotation.images(
                dir == null ? null : new File(dir, "wallpapers")));
        // A theme with a single wallpaper.jpg offers that picture too (Config maps the choice back to it).
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);
        String[] labels = new String[images.size() + 2];
        for (int i = 0; i < images.size(); i++) labels[i] = wallpaperLabel(images.get(i).getName());
        labels[images.size()] = getString(R.string.wallpaper_none);
        labels[images.size() + 1] = getString(R.string.wallpaper_pick_image);
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        int checked = "none".equals(current) ? images.size()
                : indexOf(images, current);
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == images.size() + 1) {
                        pickImage();
                        return;
                    }
                    if (which == images.size()) {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none").apply();
                    } else {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY,
                                images.get(which).getName()).apply();
                    }
                    changed();
                    Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNeutralButton(R.string.wallpaper_theme_default, (dialog, which) -> {
                    choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
                    changed();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** A SeekBar listener that only cares about changes the owner made (not the ones refresh() makes). */
    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        abstract void onUserChange(int progress);

        @Override
        public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
            if (fromUser) onUserChange(progress);
        }

        @Override
        public void onStartTrackingTouch(SeekBar bar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar bar) {
        }
    }

    /** A saved picture from the owner's storage reads "Your picture" instead of its generated file name. */
    private String wallpaperLabel(String fileName) {
        return WallpaperImport.isImported(fileName) ? getString(R.string.wallpaper_custom) : fileName;
    }

    /**
     * Opens the system picture picker. Android TV firmware often ships without one, so a second kind of picker is
     * tried and, when there is none, the owner is told about the wallpapers folder instead.
     */
    private void pickImage() {
        Intent[] pickers = {
                new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),
                new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")};
        for (Intent picker : pickers) {
            try {
                startActivityForResult(picker, PICK_IMAGE_REQUEST);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // try the next kind of picker
            }
        }
        Toast.makeText(this, R.string.wallpaper_pick_unavailable, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // back from the Home picker or the accessibility list: show what the owner changed there
        if (defaultHomeChoice != null) refresh();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == PICK_IMAGE_REQUEST) importWallpaper(data.getData());
        else if (requestCode == PICK_THEME_REQUEST) importTheme(data.getData());
    }

    /**
     * Opens the system file picker for a theme: a config.json, a theme .zip or a picture. Like pickImage, a TV without
     * a picker falls back to a toast that points at "Receive a theme from a phone".
     */
    private void pickThemeFile() {
        String[] types = {"application/zip", "application/x-zip-compressed", "application/json", "text/plain",
                "application/octet-stream", "image/*"};
        Intent[] pickers = {
                new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                        .putExtra(Intent.EXTRA_MIME_TYPES, types),
                new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")};
        for (Intent picker : pickers) {
            try {
                startActivityForResult(picker, PICK_THEME_REQUEST);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // try the next kind of picker
            }
        }
        Toast.makeText(this, R.string.theme_import_unavailable, Toast.LENGTH_LONG).show();
    }

    /** Copies the picked file aside and puts it into the theme off the main thread, then says how it went. */
    private void importTheme(Uri uri) {
        final android.content.ContentResolver resolver = getContentResolver();
        final File cache = getCacheDir();
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            String name = ThemeIntake.displayName(resolver, uri);
            String text;
            File temp = null;
            try {
                ThemeImport.Kind kind = ThemeImport.kind(name);
                if (kind == ThemeImport.Kind.UNKNOWN) throw new ThemeImport.ImportException(ThemeImport.Problem.UNSUPPORTED);
                temp = ThemeIntake.copyToTemp(resolver, uri, cache, ThemeImport.maxBytes(kind));
                text = ThemeIntake.summary(app.getResources(), ThemeIntake.ingest(app, temp, name));
            } catch (ThemeImport.ImportException e) {
                text = getString(R.string.theme_import_failed, ThemeIntake.message(app.getResources(), e.problem));
            } finally {
                if (temp != null) temp.delete();
            }
            final String shown = text;
            runOnUiThread(() -> {
                changed();   // Home rebuilds with the new theme when this screen is left
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(this, shown, Toast.LENGTH_LONG).show();
                recreate();   // a new config.json resets the choices made on the TV: read them again
            });
        }, "theme-import").start();
    }

    /** Scales and saves the picked picture off the main thread, then makes it the wallpaper. */
    private void importWallpaper(Uri uri) {
        File dir = Config.dir(this);
        if (dir == null) {
            Toast.makeText(this, R.string.wallpaper_pick_failed, Toast.LENGTH_LONG).show();
            return;
        }
        final File folder = new File(dir, "wallpapers");
        final android.content.ContentResolver resolver = getContentResolver();
        final int screenW = Math.max(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        final int screenH = Math.min(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        new Thread(() -> {
            String name = WallpaperImport.save(resolver, uri, folder, screenW, screenH);
            if (name != null) choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, name).apply();
            runOnUiThread(() -> {
                if (name != null) changed();   // bumps the change count so Home rebuilds with the new wallpaper
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(this, name != null ? R.string.wallpaper_applied : R.string.wallpaper_pick_failed,
                        Toast.LENGTH_SHORT).show();
                refresh();
            });
        }, "wallpaper-import").start();
    }

    private static int indexOf(List<File> images, String name) {
        if (name == null) return -1;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    /**
     * A choice was saved. Home rebuilds when it next resumes because the change count moved, whether these
     * settings are left with Back or with the remote's Home button (which cancels the activity result).
     */
    private void changed() {
        setResult(RESULT_OK);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        changed();
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        for (int i = 0; i < rowChoices.length; i++) {
            rowChoices[i].setSelected(rowsBelowDock == i);
        }
        for (int i = 0; i < dockChoices.length; i++) {
            dockChoices[i].setSelected(dockOpacity == DOCK_OPACITIES[i]);
        }
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        wallpaperChoice.setText(getString(R.string.wallpaper_state,
                "none".equals(wallpaperCurrent) ? getString(R.string.wallpaper_none)
                        : wallpaperCurrent != null ? wallpaperLabel(wallpaperCurrent)
                        : getString(R.string.wallpaper_theme_default)));
        defaultHomeChoice.setText(getString(R.string.default_home_state,
                getString(HomeTakeover.isDefaultHome(this) ? R.string.default_home_yes : R.string.default_home_no)));
        stockRedirectChoice.setText(getString(R.string.stock_redirect_state, getString(!stockRedirect ? R.string.off
                : HomeTakeover.serviceEnabled(this, HomeKeyService.class) ? R.string.on : R.string.stock_redirect_waiting)));
        dockOnlyChoice.setText(getString(R.string.dock_only_state, getString(dockOnly ? R.string.on : R.string.off)));
        lunarChoice.setText(getString(R.string.lunar_state, getString(lunar ? R.string.on : R.string.off)));
        dockDownLabel.setText(dockDown == 0 ? getString(R.string.dock_position_off)
                : getString(R.string.dock_position_state, dockDown));
        if (dockDownSlider.getProgress() != dockDown / HomeChoices.DOCK_DOWN_STEP_DP) {
            dockDownSlider.setProgress(dockDown / HomeChoices.DOCK_DOWN_STEP_DP);
        }
        heroTitleLabel.setText(heroTitleDelta == 0 ? getString(R.string.hero_title_default)
                : getString(R.string.hero_title_state, heroTitleDelta));
        int heroProgress = HomeChoices.heroTitleProgress(heroTitleDelta);
        if (heroTitleSlider.getProgress() != heroProgress) heroTitleSlider.setProgress(heroProgress);
        followThemeChoice.setSelected(!hasOnTvChoices());
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY) || choices.contains(Config.DOCK_OPACITY_KEY)
                || HomeChoices.anySet(choices);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
