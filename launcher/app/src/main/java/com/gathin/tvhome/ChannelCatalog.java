package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The TV channels a card on Home can open straight into another app, built in so that choosing them needs no network
 * (the launcher makes no requests of its own): the nine VTV channels in VTVgo and the 61 channels of FPT Play.
 * Both the list and the link forms are taken from cleantv-fork (OKenh.kt, PhienLamViec.kt) and have not been
 * re-checked here: VTVgo opens vtvgo://play?mediaType=CHANNEL&amp;contentId=&lt;id&gt;, FPT Play (net.fptplay.ottbox) an
 * https://fptplay.vn/xem-truyen-hinh/&lt;slug&gt; link. The fork says it tried the FPT Play link with VTV1 HD on 26/10/2026.
 * Any other channel can be added by hand with link_tiles in a theme's config.json (see {@link LinkTiles}).
 */
final class ChannelCatalog {
    static final String VTVGO_PACKAGE = "vn.vtv.vtvgotv";
    static final String FPT_PACKAGE = "net.fptplay.ottbox";
    /** The most channel cards that can be on Home at once (the same cap as link_tiles). */
    static final int MAX_SELECTED = 12;
    static final String GROUP_VTVGO = "vtvgo", GROUP_NATIONAL = "qg", GROUP_LOCAL = "dp";

    private static final Pattern ID = Pattern.compile("[a-z0-9:_-]{1,60}");

    static final class Channel {
        /** Stable key kept in the owner's choices: "vtvgo:1", "fpt:vtv1-hd". */
        final String id;
        /** The name on the card (at most 24 characters, as link_tiles requires). */
        final String label;
        /** The app that opens the card. */
        final String target;
        final String uri;
        final String group;
        final int color;

        Channel(String id, String label, String target, String uri, String group, int color) {
            this.id = id;
            this.label = label;
            this.target = target;
            this.uri = uri;
            this.group = group;
            this.color = color;
        }

        /** The package name the card goes by in the dock order and the saved order. */
        String pkg() {
            return LinkTiles.PREFIX + slug(label);
        }
    }

    private static final String[][] VTVGO = {
            {"VTV1", "1", "D71920"},
            {"VTV2", "2", "2E7D32"},
            {"VTV3", "3", "E8590C"},
            {"VTV4", "4", "1565C0"},
            {"VTV5", "5", "6A1B9A"},
            {"VTV6", "13", "00897B"},
            {"VTV7", "27", "F9A825"},
            {"VTV8", "36", "AD1457"},
            {"VTV9", "39", "283593"}
    };

    /** {name, slug of /xem-truyen-hinh/, group}, as listed in cleantv-fork's OKenh.kt. */
    private static final String[][] FPT = {
            {"VTV1 HD", "vtv1-hd", "qg"},
            {"VTV2 HD", "vtv2-hd", "qg"},
            {"VTV3 HD", "vtv3-hd", "qg"},
            {"VTV4 HD", "vtv4-hd", "qg"},
            {"VTV5 HD", "vtv5-hd", "qg"},
            {"VTV5 Tây Nam Bộ", "vtv5-tay-nam-bo", "qg"},
            {"VTV5 Tây Nguyên", "vtv5-tay-nguyen", "qg"},
            {"VTV6 HD", "vtv6-hd", "qg"},
            {"VTV7 HD", "vtv7-hd", "qg"},
            {"VTV8 HD", "vtv8-hd", "qg"},
            {"VTV9 HD", "vtv9-hd", "qg"},
            {"ANTV HD", "antv", "qg"},
            {"HTV1", "htv1", "qg"},
            {"HTV2 HD", "htv2", "qg"},
            {"HTV3", "htv3", "qg"},
            {"HTV4", "htv4", "qg"},
            {"HTV7 HD", "htv7-hd", "qg"},
            {"HTV9 HD", "htv9-hd", "qg"},
            {"HTVC Phim", "htvc-phim", "qg"},
            {"HTVC Thuần Việt", "htvc-thuan-viet-hd", "qg"},
            {"HTVC Du Lịch", "htvc-du-lich", "qg"},
            {"HTVC Gia Đình", "htvc-gia-dinh", "qg"},
            {"HTVC Phụ Nữ", "htvc-phu-nu", "qg"},
            {"HTVC Plus", "htvc-plus", "qg"},
            {"Vĩnh Long 1", "thvl1", "dp"},
            {"Vĩnh Long 2", "vinh-long-2", "dp"},
            {"Vĩnh Long 3", "vinh-long-3", "dp"},
            {"Cần Thơ 1", "can-tho", "dp"},
            {"Hải Phòng", "hai-phong", "dp"},
            {"Quảng Ninh 1", "quang-ninh-1", "dp"},
            {"Quảng Ninh 3", "quang-ninh-3", "dp"},
            {"Hưng Yên", "hung-yen", "dp"},
            {"Ninh Bình", "ninh-binh", "dp"},
            {"Phú Thọ", "phu-tho", "dp"},
            {"Thái Nguyên", "thai-nguyen", "dp"},
            {"Tuyên Quang", "tuyen-quang", "dp"},
            {"Lào Cai", "lao-cai", "dp"},
            {"Lai Châu", "lai-chau", "dp"},
            {"Điện Biên", "dien-bien", "dp"},
            {"Sơn La", "son-la", "dp"},
            {"Lạng Sơn", "lang-son", "dp"},
            {"Cao Bằng", "cao-bang", "dp"},
            {"Thanh Hóa", "thanh-hoa", "dp"},
            {"Nghệ An", "nghe-an", "dp"},
            {"Hà Tĩnh", "ha-tinh", "dp"},
            {"Quảng Trị", "quang-tri", "dp"},
            {"Thừa Thiên Huế", "thua-thien-hue", "dp"},
            {"Đà Nẵng 1", "da-nang-1", "dp"},
            {"Đà Nẵng 2", "da-nang-2", "dp"},
            {"Quảng Ngãi", "quang-ngai-1", "dp"},
            {"Khánh Hòa", "khanh-hoa", "dp"},
            {"Đắk Lắk", "dak-lak", "dp"},
            {"Lâm Đồng 1", "lam-dong", "dp"},
            {"Lâm Đồng 2", "lam-dong-2", "dp"},
            {"Tây Ninh", "tay-ninh", "dp"},
            {"Đồng Nai 1", "dong-nai-1", "dp"},
            {"Đồng Nai 2", "dong-nai-2", "dp"},
            {"Đồng Tháp 1", "dong-thap", "dp"},
            {"An Giang 1", "an-giang-1", "dp"},
            {"An Giang 2", "an-giang", "dp"},
            {"Cà Mau", "ca-mau", "dp"}
    };

    private static final List<Channel> ALL = build();

    private ChannelCatalog() {
    }

    private static List<Channel> build() {
        List<Channel> out = new ArrayList<>();
        for (String[] v : VTVGO) {
            out.add(new Channel("vtvgo:" + v[1], v[0], VTVGO_PACKAGE, uriVtvGo(v[1]), GROUP_VTVGO,
                    0xFF000000 | Integer.parseInt(v[2], 16)));
        }
        for (String[] f : FPT) {
            out.add(new Channel("fpt:" + f[1], f[0], FPT_PACKAGE, uriFpt(f[1]), f[2], 0xFFE65100));
        }
        return Collections.unmodifiableList(out);
    }

    static String uriVtvGo(String channelId) {
        return "vtvgo://play?mediaType=CHANNEL&contentId=" + channelId;
    }

    static String uriFpt(String slug) {
        return "https://fptplay.vn/xem-truyen-hinh/" + slug;
    }

    static List<Channel> all() {
        return ALL;
    }

    static Channel byId(String id) {
        for (Channel c : ALL) if (c.id.equals(id)) return c;
        return null;
    }

    /** The lower-case a-z0-9 form of a label, which names a card's package and its kenh-&lt;slug&gt;.jpg picture. */
    static String slug(String label) {
        return label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** The ids as the owner keeps them: comma separated, in the order picked. */
    static String encode(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        return sb.toString();
    }

    /** The ids in a saved text, in order, without repeats, junk or more than {@link #MAX_SELECTED}. */
    static List<String> decode(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String part : text.split(",")) {
            String id = part.trim();
            if (ID.matcher(id).matches() && !out.contains(id) && out.size() < MAX_SELECTED) out.add(id);
        }
        return out;
    }

    /** The channels for these ids, in order; ids that are not in the catalog are skipped. */
    static List<Channel> resolve(List<String> ids) {
        List<Channel> out = new ArrayList<>();
        for (String id : ids) {
            Channel c = byId(id);
            if (c != null) out.add(c);
        }
        return out;
    }

    /**
     * The ids after switching one channel on (added last) or off. Switching on when {@link #MAX_SELECTED} are already
     * chosen leaves the list as it is: the caller tells the owner.
     */
    static List<String> toggle(List<String> ids, String id, boolean on) {
        List<String> next = new ArrayList<>(ids);
        if (on) {
            if (!next.contains(id) && next.size() < MAX_SELECTED) next.add(id);
        } else {
            next.remove(id);
        }
        return next;
    }
}
