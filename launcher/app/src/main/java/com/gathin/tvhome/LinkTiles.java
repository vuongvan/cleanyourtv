package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Cards on Home that open a TV channel straight inside another app (VTVgo, FPT Play ...). They are not installed apps:
 * each is a made-up package {@code vgs.kenh.<slug>} that AppAdapter lists beside the real ones, and a press fires a
 * VIEW intent with the channel's link at the app that plays it. Ported from cleantv-fork's launcher patch.
 *
 * <p>Two sources, the owner's first: the channels picked in Launcher settings (HomeChoices, from
 * {@link ChannelCatalog}), then a theme's config.json {@code "link_tiles"}, lines "Name|package|uri|#RRGGBB" for any
 * other channel or app. A card whose app is not installed is left out. Its picture is
 * {@code kenh-<slug>.jpg} beside config.json, else the logo bundled in assets/kenh, else a drawn card of its colour.
 * No network: nothing here is fetched.
 */
final class LinkTiles {
    /** Package-name prefix of every card; ThemeFiles accepts kenh-*.jpg for the same names. */
    static final String PREFIX = "vgs.kenh.";
    private static final int MAX = ChannelCatalog.MAX_SELECTED;
    private static final long MAX_PICTURE_BYTES = 512 * 1024;
    private static final int MAX_PICTURE_WIDTH = 640;
    private static final int DEFAULT_COLOR = 0xFF37474F;
    /** TV360 for Android TV: it is not opened by a link but by a voice-assistant command (see {@link #commandFor}). */
    static final String TV360_PACKAGE = "com.viettel.tv360.tv";
    /** A TV360 card's link is "tv360:" and the channel number in TV360's own list, e.g. tv360:001 (VTV1). */
    static final String TV360_SCHEME = "tv360:";

    private LinkTiles() {
    }

    static final class Link {
        final String label, target, uri, pkg;
        final int color;
        Drawable picture;

        Link(String label, String target, String uri, int color) {
            this.label = label;
            this.target = target;
            this.uri = uri;
            this.color = color;
            this.pkg = PREFIX + ChannelCatalog.slug(label);
        }
    }

    /** True for the made-up package name of a card (so it never reaches Settings or the app library). */
    static boolean isLinkPackage(String pkg) {
        return pkg != null && pkg.startsWith(PREFIX);
    }

    /** The cards' package names for the picked channels, in the order picked, installed or not: they lead the dock. */
    static List<String> chosenPackages(Context ctx) {
        List<String> out = new ArrayList<>();
        for (ChannelCatalog.Channel c : ChannelCatalog.resolve(HomeChoices.channelIds(ctx))) out.add(c.pkg());
        return out;
    }

    /** The cards to show now: the picked channels, then the theme's link_tiles; at most {@link #MAX}, no repeats. */
    static List<Link> load(Context ctx) {
        List<Link> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ChannelCatalog.Channel c : ChannelCatalog.resolve(HomeChoices.channelIds(ctx))) {
            if (out.size() >= MAX) break;
            if (!installed(ctx, c.target)) continue;
            Link l = new Link(c.label, c.target, c.uri, c.color);
            if (seen.add(l.pkg)) out.add(l);
        }
        File dir = Config.dir(ctx);
        for (Link l : fromConfig(ctx, dir, true)) {
            if (out.size() >= MAX) break;
            if (seen.add(l.pkg)) out.add(l);
        }
        for (Link l : out) l.picture = picture(ctx, dir, l);
        return out;
    }

    /** The theme's link_tiles that parse, installed or not: what the channel screen lists so they can be shown or hidden. */
    static List<Link> configLinks(Context ctx) {
        return fromConfig(ctx, Config.dir(ctx), false);
    }

    private static List<Link> fromConfig(Context ctx, File dir, boolean onlyInstalled) {
        List<Link> out = new ArrayList<>();
        if (dir == null) return out;
        File file = new File(dir, Config.FILE_NAME);
        if (!file.isFile() || file.length() > ThemeImport.MAX_CONFIG_BYTES) return out;
        try (InputStream in = new FileInputStream(file)) {
            JSONArray lines = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8)).optJSONArray("link_tiles");
            if (lines == null) return out;
            for (int i = 0; i < lines.length() && out.size() < MAX; i++) {
                Link l = parse(lines.optString(i, ""));
                if (l != null && (!onlyInstalled || installed(ctx, l.target))) out.add(l);
            }
        } catch (Exception ignored) {
            // an unreadable config shows no extra cards; Config reports its own problems
        }
        return out;
    }

    /** "Name|package|uri|#RRGGBB" (the colour is optional) -> a card, or null when the line is not usable. */
    static Link parse(String line) {
        if (line == null) return null;
        String[] p = line.split("\\|");
        if (p.length < 3) return null;
        String label = p[0].trim();
        String target = p[1].trim();
        String uri = p[2].trim();
        if (label.isEmpty() || label.length() > 24 || ChannelCatalog.slug(label).isEmpty()) return null;
        if (!target.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+") || uri.isEmpty()) return null;
        if (uri.startsWith(TV360_SCHEME)) {
            String number = tv360Number(uri.substring(TV360_SCHEME.length()));
            if (number == null || !TV360_PACKAGE.equals(target)) return null;   // a TV360 card needs TV360 and a real number
            uri = TV360_SCHEME + number;
        }
        return new Link(label, target, uri, p.length >= 4 ? colorOf(p[3], DEFAULT_COLOR) : DEFAULT_COLOR);
    }

    /** "1", "01" or " 001 " as TV360's three-digit channel number "001"; anything outside 1 to 999 gives null. */
    static String tv360Number(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (!t.matches("[0-9]{1,3}")) return null;
        int n = Integer.parseInt(t);
        return n >= 1 ? String.format(java.util.Locale.ROOT, "%03d", n) : null;
    }

    /** The channel name TV360 is given besides the number: the card's label without a trailing "(...)" such as "(TV360)". */
    static String tvName(String label) {
        return label == null ? "" : label.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
    }

    /**
     * The intent that opens a card's channel. TV360 takes the command its voice assistant gets ("watch VTV1 on TV360"):
     * the SWITCH_CHANNEL data, the channel number and the name, and finds the channel in its own list, starting TV360,
     * bringing it up or switching channel. Any other app is sent its link by an explicit VIEW. Both forms come from
     * the kenh module (MoKenh.kt) and are not checked against a TV here.
     */
    static android.content.Intent commandFor(String target, String uri, String label) {
        if (uri.startsWith(TV360_SCHEME)) {
            return new android.content.Intent(TV360_PACKAGE + ".VIEW", android.net.Uri.parse("content://" + TV360_PACKAGE + "/SWITCH_CHANNEL"))
                    .setClassName(TV360_PACKAGE, TV360_PACKAGE + ".assistant.PostmanAssistant")
                    .putExtra("number_channel", uri.substring(TV360_SCHEME.length()))
                    .putExtra("name_channel", tvName(label));
        }
        return new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri)).setPackage(target);
    }

    /**
     * Opens the channel; when the app does not take the command or link (a later version changed it) the app itself is
     * opened instead. Returns false when neither could be started.
     */
    static boolean launch(Context ctx, String target, String uri, String label) {
        List<android.content.Intent> tries = new ArrayList<>();
        tries.add(commandFor(target, uri, label));
        android.content.Intent app = ctx.getPackageManager().getLeanbackLaunchIntentForPackage(target);
        if (app == null) app = ctx.getPackageManager().getLaunchIntentForPackage(target);
        if (app != null) tries.add(app);
        for (android.content.Intent i : tries) {
            try {
                ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        | android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
                return true;
            } catch (android.content.ActivityNotFoundException | SecurityException ignored) {
                // the next way
            }
        }
        return false;
    }

    /** "#RRGGBB" (the # is optional) as an opaque colour; anything else gives {@code fallback}. */
    static int colorOf(String text, int fallback) {
        String t = text == null ? "" : text.trim();
        if (t.startsWith("#")) t = t.substring(1);
        if (!t.matches("[0-9A-Fa-f]{6}")) return fallback;
        return 0xFF000000 | Integer.parseInt(t, 16);
    }

    static boolean installed(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static Drawable picture(Context ctx, File dir, Link l) {
        String slug = ChannelCatalog.slug(l.label);
        if (dir != null) {
            File f = new File(dir, "kenh-" + slug + ".jpg");
            if (f.isFile() && f.length() <= MAX_PICTURE_BYTES) {
                Bitmap bmp = decodeScaled(f);
                if (bmp != null) return new BitmapDrawable(ctx.getResources(), bmp);
            }
        }
        try (InputStream in = ctx.getAssets().open("kenh/" + slug + ".jpg")) {
            Bitmap bmp = BitmapFactory.decodeStream(in);
            if (bmp != null) return new BitmapDrawable(ctx.getResources(), bmp);
        } catch (IOException ignored) {
            // no bundled logo for this channel: draw the card
        }
        return new Face(l.label, l.color);
    }

    private static Bitmap decodeScaled(File f) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        if (bounds.outWidth <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, Integer.highestOneBit(Math.max(1, bounds.outWidth / MAX_PICTURE_WIDTH)));
        return BitmapFactory.decodeFile(f.getPath(), options);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    /** A card with no picture: the channel's colour as a gradient, its name in white across the middle. */
    static final class Face extends Drawable {
        private final String text;
        private final int color;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);

        Face(String text, int color) {
            this.text = text;
            this.color = color;
            ink.setColor(Color.WHITE);
            ink.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            ink.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onBoundsChange(Rect b) {
            if (b.isEmpty()) return;
            int dark = Color.rgb((int) (Color.red(color) * 0.55f), (int) (Color.green(color) * 0.55f),
                    (int) (Color.blue(color) * 0.55f));
            fill.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom, color, dark, Shader.TileMode.CLAMP));
            float size = b.height() * 0.30f;
            ink.setTextSize(size);
            float width = ink.measureText(text);
            float room = b.width() * 0.86f;
            if (width > room) ink.setTextSize(size * room / width);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            canvas.drawRect(b, fill);
            canvas.drawText(text, b.exactCenterX(), b.exactCenterY() - (ink.ascent() + ink.descent()) / 2f, ink);
        }

        @Override
        public void setAlpha(int alpha) {
            fill.setAlpha(alpha);
            ink.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            fill.setColorFilter(filter);
            ink.setColorFilter(filter);
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }
}
