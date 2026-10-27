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
 * opening its channel straight in VTVgo or FPT Play. The pick is kept in HomeChoices and Home rebuilds when this screen
 * is left. A channel the owner removed from Home through the tile menu counts as not ticked; ticking it brings it back.
 */
public class ChannelTilesActivity extends Activity {
    private final Map<String, TextView> rows = new HashMap<>();
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
                TextView header = new TextView(this);
                header.setText(groupTitle(group));
                header.setTextColor(0xFFB9E7C7);
                header.setTextSize(20);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                p.topMargin = dp(first ? 18 : 26);
                p.bottomMargin = dp(6);
                list.addView(header, p);
                first = false;
            }
            TextView r = row(list, "");
            rows.put(c.id, r);
            r.setOnClickListener(v -> toggle(c));
        }
        refresh();
        clear.requestFocus();
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
            if (!ids.contains(c.id) && ids.size() >= ChannelCatalog.MAX_SELECTED) {
                Toast.makeText(this, getString(R.string.channel_tiles_limit, ChannelCatalog.MAX_SELECTED), Toast.LENGTH_SHORT).show();
                return;
            }
            HomeChoices.setChannelIds(this, ChannelCatalog.toggle(ids, c.id, true));
            if (removed.contains(c.pkg())) LibraryApps.save(this, c.pkg(), true);   // bring back a card removed from Home
        }
        ThemeIntake.notifyChanged(this);
        refresh();
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
        count.setText(getString(R.string.channel_tiles_count, on, ChannelCatalog.MAX_SELECTED));
    }
}
