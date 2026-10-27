package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ChannelCatalogTest {

    @Test
    public void theCatalogHasTheNineVtvAndSixtyOneFptChannels() {
        int vtv = 0, fpt = 0;
        for (ChannelCatalog.Channel c : ChannelCatalog.all()) {
            if (c.target.equals(ChannelCatalog.VTVGO_PACKAGE)) vtv++;
            if (c.target.equals(ChannelCatalog.FPT_PACKAGE)) fpt++;
        }
        assertEquals(9, vtv);
        assertEquals(61, fpt);
    }

    @Test
    public void idsLabelsAndCardPackagesAreAllDifferent() {
        Set<String> ids = new HashSet<>(), pkgs = new HashSet<>(), labels = new HashSet<>();
        for (ChannelCatalog.Channel c : ChannelCatalog.all()) {
            assertTrue(c.id, ids.add(c.id));
            assertTrue(c.pkg(), pkgs.add(c.pkg()));
            assertTrue(c.label, labels.add(c.label));
        }
    }

    @Test
    public void everyChannelFitsWhatLinkTilesAccepts() {
        for (ChannelCatalog.Channel c : ChannelCatalog.all()) {
            assertTrue(c.label, c.label.length() > 0 && c.label.length() <= 24);
            assertFalse(c.label, ChannelCatalog.slug(c.label).isEmpty());
            assertTrue(c.pkg(), c.pkg().startsWith(LinkTiles.PREFIX));
            assertTrue(c.uri, c.uri.startsWith("vtvgo://") || c.uri.startsWith("https://fptplay.vn/xem-truyen-hinh/"));
            assertEquals(0xFF000000, c.color & 0xFF000000);
            // the same line a theme could carry must read back as this card
            LinkTiles.Link l = LinkTiles.parse(c.label + "|" + c.target + "|" + c.uri);
            assertEquals(c.pkg(), l.pkg);
            assertEquals(c.uri, l.uri);
        }
    }

    @Test
    public void linkFormsMatchWhatTheForkDocuments() {
        assertEquals("vtvgo://play?mediaType=CHANNEL&contentId=13", ChannelCatalog.uriVtvGo("13"));
        assertEquals("https://fptplay.vn/xem-truyen-hinh/vtv1-hd", ChannelCatalog.uriFpt("vtv1-hd"));
        assertEquals("vtvgo://play?mediaType=CHANNEL&contentId=1", ChannelCatalog.byId("vtvgo:1").uri);
        assertEquals("VTV6", ChannelCatalog.byId("vtvgo:13").label);
        assertEquals("https://fptplay.vn/xem-truyen-hinh/vtv1-hd", ChannelCatalog.byId("fpt:vtv1-hd").uri);
        assertNull(ChannelCatalog.byId("nope"));
    }

    @Test
    public void slugKeepsOnlyLowerCaseLettersAndDigits() {
        assertEquals("vtv1hd", ChannelCatalog.slug("VTV1 HD"));
        assertEquals("vtv5tynamb", ChannelCatalog.slug("VTV5 Tây Nam Bộ"));
        assertEquals("", ChannelCatalog.slug("!!!"));
    }

    @Test
    public void picksAreKeptInOrderWithoutRepeatsOrJunk() {
        assertEquals(Arrays.asList("vtvgo:1", "fpt:vtv1-hd"), ChannelCatalog.decode(" vtvgo:1, fpt:vtv1-hd ,vtvgo:1,"));
        assertEquals(new ArrayList<String>(), ChannelCatalog.decode(null));
        assertEquals(new ArrayList<String>(), ChannelCatalog.decode(""));
        assertEquals(Arrays.asList("vtvgo:2"), ChannelCatalog.decode("bad id!,<script>,vtvgo:2"));
        assertEquals("vtvgo:1,vtvgo:2", ChannelCatalog.encode(Arrays.asList("vtvgo:1", "vtvgo:2")));
    }

    @Test
    public void decodeKeepsAtMostTwelve() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) sb.append("fpt:k").append(i).append(',');
        assertEquals(ChannelCatalog.MAX_SELECTED, ChannelCatalog.decode(sb.toString()).size());
    }

    @Test
    public void unknownIdsAreSkippedWhenResolving() {
        List<ChannelCatalog.Channel> out = ChannelCatalog.resolve(Arrays.asList("vtvgo:3", "ghost", "vtvgo:1"));
        assertEquals(2, out.size());
        assertEquals("VTV3", out.get(0).label);
        assertEquals("VTV1", out.get(1).label);
    }

    @Test
    public void togglingAddsLastRemovesAndStopsAtTheLimit() {
        List<String> ids = new ArrayList<>();
        ids = ChannelCatalog.toggle(ids, "vtvgo:1", true);
        ids = ChannelCatalog.toggle(ids, "vtvgo:2", true);
        ids = ChannelCatalog.toggle(ids, "vtvgo:1", true);   // already there
        assertEquals(Arrays.asList("vtvgo:1", "vtvgo:2"), ids);
        ids = ChannelCatalog.toggle(ids, "vtvgo:1", false);
        assertEquals(Arrays.asList("vtvgo:2"), ids);
        List<String> full = new ArrayList<>();
        for (int i = 0; i < ChannelCatalog.MAX_SELECTED; i++) full.add("fpt:k" + i);
        assertEquals(full, ChannelCatalog.toggle(full, "vtvgo:9", true));
    }

    @Test
    public void linkLinesAreReadAndBadOnesRefused() {
        LinkTiles.Link l = LinkTiles.parse("VTV9|vn.vtv.vtvgotv|vtvgo://play?mediaType=CHANNEL&contentId=39|#283593");
        assertEquals("vgs.kenh.vtv9", l.pkg);
        assertEquals(0xFF283593, l.color);
        assertEquals(0xFF37474F, LinkTiles.parse("A|a.b|x://y").color);
        assertEquals(0xFF123456, LinkTiles.parse("A|a.b|x://y|123456").color);
        assertEquals(0xFF37474F, LinkTiles.parse("A|a.b|x://y|red").color);
        assertNull(LinkTiles.parse("only|two"));
        assertNull(LinkTiles.parse("|a.b|x://y"));
        assertNull(LinkTiles.parse("A|notapackage|x://y"));
        assertNull(LinkTiles.parse("!!!|a.b|x://y"));
        assertNull(LinkTiles.parse("This name is far too long to fit|a.b|x://y"));
        assertNull(LinkTiles.parse(null));
        assertTrue(LinkTiles.isLinkPackage("vgs.kenh.vtv1"));
        assertFalse(LinkTiles.isLinkPackage("com.google.android.youtube.tv"));
    }
}
