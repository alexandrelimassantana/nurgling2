package nurgling;

import haven.Coord;
import haven.Coord2d;
import haven.GItem;
import haven.GSprite;
import haven.Gob;
import haven.Loading;
import haven.MCache;
import haven.WItem;
import haven.res.lib.itemtex.ItemTex;
import haven.res.ui.stackinv.ItemStack;
import nurgling.widgets.QualityHunterContainer;
import nurgling.widgets.QualityHunterProfiles;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Armed by a flower-menu choice on a gob (any gob -- see armOnGobAction), not by continuous
 * background scanning: each arming snapshots the tracked items' per-quality unit counts and the
 * gob's position, then watches only for one of those counts to rise before the player wanders off
 * again. This relies on three things actually being true of gathering interactions: the inventory
 * cannot change between choosing the action and it resolving except through that action itself; a
 * resolved action yields only more of one already-known item at one quality; and every tracked item
 * is collected through a flower-menu action (no bare right-click gathering to also watch for). Given
 * those, there is nothing to protect against between arming and resolution, so no persistent
 * baseline is needed at all -- unlike a continuous scanner, this is idle whenever nothing is pending.
 */
public class QualityHunter {
    private static final double SCAN_INTERVAL_SECONDS = 0.2;
    private static final double NEAR_RADIUS_TILES = 3;
    private static final double DEPART_RADIUS_TILES = 5;
    private static final double HARD_TIMEOUT_SECONDS = 60;

    private static double lastScan = 0;
    private static final List<PendingPickup> pending = new ArrayList<>();

    private static final class PendingPickup {
        final double armedAt;
        final String gobName; // for log messages only
        final Map<String, Integer> props; // tracked name -> threshold, frozen at arm time
        final long segmentId;
        final Coord tileCoords;
        final Coord2d gobRc;
        boolean everNear = false;
        // Per tracked name, filled in as soon as that name's holdings are confirmed fully
        // resolved -- immediately at arm time for the overwhelming common case (seeded
        // synchronously below, before the player has even started walking), or on the first
        // clean tick afterward if some unrelated unit of that name happened to be mid-resolution
        // right as this armed. A name absent here simply hasn't had a trustworthy "before" yet.
        final Map<String, Map<Float, Integer>> baseline = new HashMap<>();

        PendingPickup(double armedAt, String gobName, Map<String, Integer> props, long segmentId, Coord tileCoords, Coord2d gobRc) {
            this.armedAt = armedAt;
            this.gobName = gobName;
            this.props = props;
            this.segmentId = segmentId;
            this.tileCoords = tileCoords;
            this.gobRc = gobRc;
        }
    }

    private static final Color LOG_INFO = new Color(150, 200, 255);
    private static final Color LOG_OK = new Color(120, 220, 120);
    private static final Color LOG_WARN = new Color(230, 190, 90);

    private static void log(String msg, Color color) {
        NGameUI gui = NUtils.getGameUI();
        if (gui != null) gui.msg("[QualityHunter] " + msg, color);
    }

    /** Name -> the lowest threshold among that name's currently-active placements (a group whose
     *  own threshold is already satisfied still wants to see the pickup, so the least demanding
     *  active group decides whether anything is worth watching for at all). A name absent here is
     *  not tracked right now: either no group lists it, or every group that does is inactive in
     *  the current profile, or every one of its entries has tracking switched off. */
    private static Map<String, Integer> trackedThresholds() {
        Map<String, Integer> thresholds = new HashMap<>();
        for (QualityHunterContainer.Entry e : QualityHunterContainer.getAllEntries()) {
            if (!e.active || !QualityHunterProfiles.isGroupActive(e.group)) continue;
            thresholds.merge(e.name, e.threshold, Math::min);
        }
        return thresholds;
    }

    /** Every currently-active group whose own threshold this quality actually clears -- what a
     *  freshly recorded mark should be filed under, so a group's minimap visibility toggle only
     *  ever hides marks that group itself would have wanted to see. */
    private static List<String> qualifyingGroups(String name, float quality) {
        Set<String> groups = new LinkedHashSet<>();
        for (QualityHunterContainer.Entry e : QualityHunterContainer.getAllEntries()) {
            if (e.name.equals(name) && e.active && quality >= e.threshold && QualityHunterProfiles.isGroupActive(e.group))
                groups.add(e.group);
        }
        return new ArrayList<>(groups);
    }

    /** Called for every confirmed flower-menu choice on a gob (NFlowerMenu.nchoose) -- deliberately
     *  not filtered to "gathering" verbs, since those vary too much by resource to enumerate, and an
     *  arming that turns out irrelevant just expires on its own once the player moves away. */
    public static void armOnGobAction(Gob gob) {
        if (gob == null || !(Boolean) NConfig.get(NConfig.Key.qualityHunterEnabled)) return;

        Map<String, Integer> props = trackedThresholds();
        if (props.isEmpty()) return;

        NGameUI gui = NUtils.getGameUI();
        if (gui == null || gui.mmap == null || gui.mmap.sessloc == null || !(gui.maininv instanceof NInventory)) return;

        long segmentId = gui.mmap.sessloc.seg.id;
        Coord tileCoords = gob.rc.floor(MCache.tilesz).add(gui.mmap.sessloc.tc);
        String gobName = (gob.ngob != null && gob.ngob.name != null) ? gob.ngob.name : "?";
        PendingPickup p = new PendingPickup(haven.Utils.rtime(), gobName, new HashMap<>(props), segmentId, tileCoords, gob.rc);

        // Seed the baseline right now, synchronously -- an adjacent gob needs no travel time at
        // all, so waiting for the next scan tick (up to SCAN_INTERVAL_SECONDS later) can be too
        // late: the pickup would already be sitting in the inventory by the time the first
        // snapshot is taken, and it would be silently absorbed into the baseline instead of ever
        // registering as an increase.
        Map<String, Map<Float, Integer>> current = new HashMap<>();
        Map<String, GSprite> spriteByName = new HashMap<>();
        Set<String> stillResolving = new HashSet<>();
        collectAll(gui, props, current, spriteByName, stillResolving);
        for (String name : props.keySet()) {
            if (!stillResolving.contains(name))
                p.baseline.put(name, current.getOrDefault(name, Collections.emptyMap()));
        }

        pending.add(p);
        log("Armed on '" + gobName + "', watching: " + String.join(", ", props.keySet()), LOG_INFO);
        if (!stillResolving.isEmpty())
            log("Baseline deferred at arm time for: " + String.join(", ", stillResolving), LOG_WARN);
    }

    public static void tick() {
        if (!(Boolean) NConfig.get(NConfig.Key.qualityHunterEnabled)) {
            if (!pending.isEmpty()) pending.clear();
            return;
        }
        if (pending.isEmpty()) return;

        double now = haven.Utils.rtime();
        if (now - lastScan < SCAN_INTERVAL_SECONDS) return;
        lastScan = now;

        NGameUI gui = NUtils.getGameUI();
        if (gui == null || !(gui.maininv instanceof NInventory)) return;
        Gob player = NUtils.player();
        if (player == null) return;

        Map<String, Integer> liveProps = trackedThresholds();
        Map<String, Map<Float, Integer>> current = new HashMap<>();
        Map<String, GSprite> spriteByName = new HashMap<>();
        Set<String> stillResolving = new HashSet<>();
        collectAll(gui, liveProps, current, spriteByName, stillResolving);

        int radius = (Integer) NConfig.get(NConfig.Key.qualityHunterMarkRadius);
        double nearWorld = NEAR_RADIUS_TILES * MCache.tilesz.x;
        double departWorld = DEPART_RADIUS_TILES * MCache.tilesz.x;

        Iterator<PendingPickup> it = pending.iterator();
        while (it.hasNext()) {
            PendingPickup p = it.next();

            if (now - p.armedAt > HARD_TIMEOUT_SECONDS) {
                it.remove(); // safety net: the action never resolved (interrupted, gob gone, etc.)
                log("Gave up on '" + p.gobName + "' - timed out after " + (int) HARD_TIMEOUT_SECONDS + "s", LOG_WARN);
                continue;
            }

            double dist = player.rc.dist(p.gobRc);
            if (dist <= nearWorld) {
                p.everNear = true;
            } else if (p.everNear && dist > departWorld) {
                it.remove(); // arrived, the action resolved, nothing tracked came of it
                log("Gave up on '" + p.gobName + "' - moved away with no matching pickup", LOG_INFO);
                continue;
            }

            String matchedName = null;
            float matchedQuality = 0;
            for (String name : p.props.keySet()) {
                if (stillResolving.contains(name)) continue; // not conclusive yet, try again next scan
                Map<Float, Integer> currentForName = current.getOrDefault(name, Collections.emptyMap());

                if (!p.baseline.containsKey(name)) {
                    // First clean read for this name since arming (it was mid-resolution at arm
                    // time) -- establish it now, this scan can't yet tell new from already-held.
                    p.baseline.put(name, currentForName);
                    log("Baseline for '" + name + "' established (deferred from arm on '" + p.gobName + "')", LOG_INFO);
                    continue;
                }

                Map<Float, Integer> before = p.baseline.get(name);
                int threshold = p.props.get(name);
                for (Map.Entry<Float, Integer> ce : currentForName.entrySet()) {
                    float q = ce.getKey();
                    if (ce.getValue() > before.getOrDefault(q, 0) && q >= threshold) {
                        matchedName = name;
                        matchedQuality = q;
                        break;
                    }
                }
                if (matchedName != null) break;
            }

            if (matchedName != null) {
                log("Pickup detected: '" + matchedName + "' q" + (int) matchedQuality
                        + " (threshold " + p.props.get(matchedName) + ") on '" + p.gobName + "'", LOG_OK);
                boolean recorded = mark(gui, matchedName, matchedQuality, spriteByName.get(matchedName), radius, p.segmentId, p.tileCoords);
                log(recorded ? "Mark recorded" : "Mark discarded - a nearby mark is already at least as good", recorded ? LOG_OK : LOG_INFO);
                it.remove();
            }
        }
    }

    private static void collectAll(NGameUI gui, Map<String, Integer> props, Map<String, Map<Float, Integer>> countsOut, Map<String, GSprite> spriteOut, Set<String> stillResolvingOut) {
        for (WItem w : ((NInventory) gui.maininv).getTopLevelItems()) {
            if (!(w.item instanceof NGItem)) continue;
            NGItem it = (NGItem) w.item;
            if (it.contents instanceof ItemStack) {
                for (GItem child : ((ItemStack) it.contents).order) {
                    if (child instanceof NGItem)
                        collect((NGItem) child, props, countsOut, spriteOut, stillResolvingOut);
                }
            } else {
                collect(it, props, countsOut, spriteOut, stillResolvingOut);
            }
        }
    }

    private static void collect(NGItem it, Map<String, Integer> props, Map<String, Map<Float, Integer>> countsOut, Map<String, GSprite> spriteOut, Set<String> stillResolvingOut) {
        String name = it.name();
        if (name == null || !props.containsKey(name)) return;
        Float q = it.quality;
        if (q == null) {
            stillResolvingOut.add(name); // this one item's quality hasn't resolved yet -- retried next scan
            return;
        }

        GItem.Amount amount = it.getInfo(GItem.Amount.class);
        int units = (amount != null) ? amount.itemnum() : 1;
        countsOut.computeIfAbsent(name, k -> new HashMap<>()).merge(q, units, Integer::sum);
        spriteOut.putIfAbsent(name, it.spr());
    }

    private static boolean mark(NGameUI gui, String name, float quality, GSprite spr, int radius, long segmentId, Coord tileCoords) {
        if (gui.labeledMarkService == null) return false;
        String label = String.format("q%.0f", quality);
        BufferedImage icon = imageFromSprite(spr);
        List<String> groups = qualifyingGroups(name, quality);
        return gui.labeledMarkService.addQualityHunterMark(label, name, quality, segmentId, tileCoords, icon, radius, groups);
    }

    private static BufferedImage imageFromSprite(GSprite spr) {
        if (spr == null) return null;
        try {
            return ItemTex.sprimg(spr);
        } catch (Loading e) {
            return null;
        }
    }
}
