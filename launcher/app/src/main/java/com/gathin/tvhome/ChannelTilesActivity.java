package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "TV channel cards": the owner ticks the channels (ChannelCatalog) that become cards at the start of the dock, each
 * opening its channel straight in VTVgo, FPT Play or TV360. The pick is kept in HomeChoices and Home rebuilds when this
 * screen is left. A channel the owner removed from Home through the tile menu counts as not ticked; ticking it brings
 * it back. The cards a theme's config.json adds (link_tiles) are listed under their own heading and can be shown or
 * hidden here the same way, so every card on Home can be managed from this one screen.
 */
public class ChannelTilesActivity extends Activity {
    private final Map<String, TextView> rows = new HashMap<>();
    private final Map<String, TextView> configRows = new HashMap<>();
    private List<LinkTiles.Link> configLinks = new ArrayList<>();
    private TextView count;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_tiles);
        count = findViewById(R.id.channelCount);
        LinearLayout list = findViewById(R.id.channelList);

        TextView clear = row(list, getString(R.string.channel_tiles_clear));
        clear.setOnClickListener(v -> {
            HomeChoices.setChannelIds(this, new ArrayList<>());
            ThemeIntake.notifyChanged(this);
            refresh();
        });

        String group = null;
        boolean first = true;
        for (ChannelCatalog.Channel c : ChannelCatalog.all()) {
            if (!c.group.equals(group)) {
                group = c.group;
                list.addView(header(groupTitle(group), first), headerParams(first));
                first = false;
            }
            TextView r = row(list, "");
            rows.put(c.id, r);
            r.setOnClickListener(v -> toggle(c));
        }
        configLinks = LinkTiles.configLinks(this);
        if (!configLinks.isEmpty()) {
            list.addView(header(getString(R.string.channel_group_config), false), headerParams(false));
            for (LinkTiles.Link l : configLinks) {
                TextView r = row(list, "");
                configRows.put(l.pkg, r);
                r.setOnClickListener(v -> toggleConfig(l));
            }
        }
        refresh();
        clear.requestFocus();
    }

    private TextView header(String text, boolean first) {
        TextView header = new TextView(this);
        header.setText(text);
        header.setTextColor(0xFFB9E7C7);
        header.setTextSize(20);
        return header;
    }

    private LinearLayout.LayoutParams headerParams(boolean first) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(first ? 18 : 26);
        p.bottomMargin = dp(6);
        return p;
    }

    private TextView row(LinearLayout list, String text) {
        TextView r = new TextView(new ContextThemeWrapper(this, R.style.SettingsRow), null, 0, R.style.SettingsRow);
        r.setText(text);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        p.topMargin = dp(6);
        list.addView(r, p);
        return r;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String groupTitle(String group) {
        switch (group) {
            case ChannelCatalog.GROUP_VTVGO:
                return getString(R.string.channel_group_vtvgo);
            case ChannelCatalog.GROUP_TV360:
                return getString(R.string.channel_group_tv360);
            case ChannelCatalog.GROUP_NATIONAL:
                return getString(R.string.channel_group_national);
            default:
                return getString(R.string.channel_group_local);
        }
    }

    /** True when the channel is picked and has not been removed from Home since. */
    private boolean isOn(ChannelCatalog.Channel c, List<String> ids, Set<String> removed) {
        return ids.contains(c.id) && !removed.contains(c.pkg());
    }

    private void toggle(ChannelCatalog.Channel c) {
        List<String> ids = HomeChoices.channelIds(this);
        Set<String> removed = LibraryApps.loadRemoved(this);
        if (isOn(c, ids, removed)) {
            HomeChoices.setChannelIds(this, ChannelCatalog.toggle(ids, c.id, false));
        } else {
            if (!ids.contains(c.id) && shownCount() >= ChannelCatalog.MAX_SELECTED) {
                Toast.makeText(this, getString(R.string.channel_tiles_limit, ChannelCatalog.MAX_SELECTED), Toast.LENGTH_SHORT).show();
                return;
            }
            HomeChoices.setChannelIds(this, ChannelCatalog.toggle(ids, c.id, true));
            if (removed.contains(c.pkg())) LibraryApps.save(this, c.pkg(), true);   // bring back a card removed from Home
        }
        ThemeIntake.notifyChanged(this);
        refresh();
    }

    /** Shows or hides a card that came from config.json: it stays in the file, Home just leaves it out while hidden. */
    private void toggleConfig(LinkTiles.Link l) {
        boolean shown = !LibraryApps.loadRemoved(this).contains(l.pkg);
        if (!shown && shownCount() >= ChannelCatalog.MAX_SELECTED) {
            Toast.makeText(this, getString(R.string.channel_tiles_limit, ChannelCatalog.MAX_SELECTED), Toast.LENGTH_SHORT).show();
            return;
        }
        LibraryApps.save(this, l.pkg, !shown);   // false = remove from Home, true = bring it back
        ThemeIntake.notifyChanged(this);
        refresh();
    }

    /** How many cards Home shows now from the picked channels and config.json together (Home keeps at most 12). */
    private int shownCount() {
        List<String> ids = HomeChoices.channelIds(this);
        Set<String> removed = LibraryApps.loadRemoved(this);
        int n = 0;
        for (ChannelCatalog.Channel c : ChannelCatalog.all()) if (isOn(c, ids, removed)) n++;
        for (LinkTiles.Link l : configLinks) if (!removed.contains(l.pkg)) n++;
        return n;
    }

    private void refresh() {
        List<String> ids = HomeChoices.channelIds(this);
        Set<String> removed = LibraryApps.loadRemoved(this);
        int on = 0;
        for (ChannelCatalog.Channel c : ChannelCatalog.all()) {
            boolean selected = isOn(c, ids, removed);
            if (selected) on++;
            String text = (selected ? "\u2713  " : "\u2003\u2003") + c.label;
            if (!LinkTiles.installed(this, c.target)) text += getString(R.string.channel_tiles_not_installed);
            rows.get(c.id).setText(text);
        }
        for (LinkTiles.Link l : configLinks) {
            boolean shown = !removed.contains(l.pkg);
            if (shown) on++;
            String text = (shown ? "\u2713  " : "\u2003\u2003") + l.label;
            if (!LinkTiles.installed(this, l.target)) text += getString(R.string.channel_tiles_not_installed);
            configRows.get(l.pkg).setText(text);
        }
        count.setText(getString(R.string.channel_tiles_count, on, ChannelCatalog.MAX_SELECTED));
    }
}
