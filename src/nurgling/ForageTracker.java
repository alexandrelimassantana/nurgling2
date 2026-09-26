package nurgling;

import haven.Coord;
import haven.GItem;
import haven.GSprite;
import haven.Gob;
import haven.Loading;
import haven.MCache;
import haven.WItem;
import haven.res.lib.itemtex.ItemTex;
import haven.res.ui.stackinv.ItemStack;
import nurgling.widgets.ForageTrackerContainer;

import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Notices when a tracked forageable's on-hand quantity, at some exact quality,
 * goes up during a brief window right after a gob flower-menu action
 * (armAfterGatherAction, called from NFlowerMenu.nchoose).
 *
 * There is no "item just entered inventory" event in this client, and quality
 * resolves asynchronously (same as everywhere else here -- see autoDrop's own
 * "recheck next tick" comments), so a gather click can't be turned into a
 * single one-shot check. What it CAN do is bound the polling to only the
 * window right after a real gather action: tick() does no inventory work at
 * all while disarmed (the common case, by far).
 *
 * Diffing by exact quality (not just a name's total count) is what lets this
 * survive a stack merge or a sort landing in the same window as a real
 * pickup: neither can raise how many units of a given quality are on hand,
 * only a real pickup (or a drop, which lowers it) can.
 *
 * Counting units correctly requires reading each item's GItem.Amount info
 * (itemnum()), not just counting NGItem objects: Haven's own auto-stacking
 * often merges a new pickup into an existing same-quality pile by bumping
 * that ONE object's amount rather than creating a new object, so counting
 * objects instead of units would silently miss exactly that case -- and the
 * more of an item already on hand, the more often a fresh pickup lands on
 * an existing pile rather than starting a new one.
 */
public class ForageTracker {
    // Generous on purpose: some gather actions run a multi-second action bar
    // before the item is actually granted, and re-arming while already armed
    // (below) extends this rather than resetting it, so a longer value here
    // only matters for a single isolated click with an unusually slow action.
    private static final double ARM_WINDOW_SECONDS = 10.0;
    private static final double POLL_INTERVAL_SECONDS = 0.2; // only spent while armed

    private static double armedUntil = 0;
    private static double lastPoll = 0;
    // Per tracked name, last known unit count per exact quality -- the baseline for the
    // current gather streak. Null while disarmed.
    private static Map<String, Map<Float, Integer>> baseline = null;

    public static void armAfterGatherAction() {
        if (!(Boolean) NConfig.get(NConfig.Key.forageTrackerEnabled)) return;
        double now = haven.Utils.rtime();

        // Already watching a still-pending pickup -- extend the window instead
        // of replacing the baseline. Foraging one bush right after another
        // (very normal play) fires this before the first item has landed; a
        // fresh snapshot taken now would already include it, and it could
        // never be recognized as new again. Anchoring the baseline to the
        // start of the streak instead means every pickup during the whole
        // streak is still visible as an increase, however many gathers long.
        if (armedUntil != 0 && now <= armedUntil) {
            armedUntil = now + ARM_WINDOW_SECONDS;
            return;
        }

        NGameUI gui = NUtils.getGameUI();
        if (gui == null || !(gui.maininv instanceof NInventory)) return;
        Map<String, Integer> props = ForageTrackerContainer.getForageProps();
        if (props.isEmpty()) return;

        Map<String, GSprite> ignored = new HashMap<>();
        baseline = new HashMap<>();
        collectAll(gui, props, baseline, ignored);
        armedUntil = now + ARM_WINDOW_SECONDS;
        lastPoll = 0;
    }

    public static void tick() {
        if (armedUntil == 0) return; // disarmed: no inventory access at all, negligible even at full frame rate

        double now = haven.Utils.rtime();
        if (now > armedUntil) {
            disarm();
            return;
        }
        if (now - lastPoll < POLL_INTERVAL_SECONDS) return;
        lastPoll = now;

        NGameUI gui = NUtils.getGameUI();
        if (gui == null || !(gui.maininv instanceof NInventory) || baseline == null) {
            disarm();
            return;
        }
        Map<String, Integer> props = ForageTrackerContainer.getForageProps();
        if (props.isEmpty()) {
            disarm();
            return;
        }

        Map<String, Map<Float, Integer>> currentByName = new HashMap<>();
        Map<String, GSprite> spriteByName = new HashMap<>();
        collectAll(gui, props, currentByName, spriteByName);

        // Mark every quality that has genuinely gained units since the start of this
        // streak, not just the first one found -- a single streak (or one long-held-open
        // window covering several back-to-back gathers) can easily produce more than one
        // qualifying pickup before a poll gets to look.
        for (Map.Entry<String, Map<Float, Integer>> e : currentByName.entrySet()) {
            String name = e.getKey();
            Map<Float, Integer> current = e.getValue();
            Map<Float, Integer> before = baseline.getOrDefault(name, Collections.emptyMap());
            int threshold = props.get(name);

            for (Map.Entry<Float, Integer> ce : current.entrySet()) {
                float q = ce.getKey();
                if (ce.getValue() > before.getOrDefault(q, 0) && q >= threshold) {
                    mark(gui, name, q, spriteByName.get(name));
                }
            }
            // Replacing (not merging into) this name's baseline is what lets a later
            // drop-then-reacquire of the same exact quality still register as new: once
            // a quality's units are gone, it simply isn't in `current` next time, so its
            // baseline count goes back to zero.
            baseline.put(name, current);
        }
    }

    private static void disarm() {
        armedUntil = 0;
        baseline = null;
    }

    private static void collectAll(NGameUI gui, Map<String, Integer> props, Map<String, Map<Float, Integer>> countsOut, Map<String, GSprite> spriteOut) {
        for (WItem w : ((NInventory) gui.maininv).getTopLevelItems()) {
            if (!(w.item instanceof NGItem)) continue;
            NGItem it = (NGItem) w.item;
            if (it.contents instanceof ItemStack) {
                for (GItem child : ((ItemStack) it.contents).order) {
                    if (child instanceof NGItem)
                        collect((NGItem) child, props, countsOut, spriteOut);
                }
            } else {
                collect(it, props, countsOut, spriteOut);
            }
        }
    }

    private static void collect(NGItem it, Map<String, Integer> props, Map<String, Map<Float, Integer>> countsOut, Map<String, GSprite> spriteOut) {
        String name = it.name();
        if (name == null || !props.containsKey(name)) return;
        Float q = it.quality;
        if (q == null) return; // this one item's quality hasn't resolved yet -- its contribution is retried once it has, other items of the same name are unaffected

        GItem.Amount amount = it.getInfo(GItem.Amount.class);
        int units = (amount != null) ? amount.itemnum() : 1;
        countsOut.computeIfAbsent(name, k -> new HashMap<>()).merge(q, units, Integer::sum);
        spriteOut.putIfAbsent(name, it.spr());
    }

    private static void mark(NGameUI gui, String name, float quality, GSprite spr) {
        if (gui.mmap == null || gui.mmap.sessloc == null || gui.labeledMarkService == null) return;
        Gob player = NUtils.player();
        if (player == null) return;

        long segmentId = gui.mmap.sessloc.seg.id;
        Coord tileCoords = player.rc.floor(MCache.tilesz).add(gui.mmap.sessloc.tc);
        String label = String.format("q%.0f", quality);
        BufferedImage icon = imageFromSprite(spr);
        int radius = (Integer) NConfig.get(NConfig.Key.forageTrackerMarkRadius);
        gui.labeledMarkService.addForageMark(label, name, quality, segmentId, tileCoords, icon, radius);
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
