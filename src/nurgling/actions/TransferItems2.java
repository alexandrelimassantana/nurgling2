package nurgling.actions;

import haven.WItem;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.areas.NContext;
import nurgling.tools.Container;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.TreeMap;
import java.util.*;

public class TransferItems2 implements Action
{
    final NContext cnt;
    HashSet<String> items;

    static HashSet<String> orderList = new HashSet<>();
    static {
        orderList.add("Moose Antlers");
        orderList.add("Flipper Bones");
        orderList.add("Red Deer Antlers");
        orderList.add("Wolf's Claws");
        orderList.add("Bear Tooth");
        orderList.add("Lynx Claws");
        orderList.add("Boar Tusk");
        orderList.add("Billygoat Horn");
        orderList.add("Bog Turtle Shell");
        orderList.add("Boreworm Beak");
        orderList.add("Cachalot Tooth");
        orderList.add("Roe Deer Antlers");
        orderList.add("Wildgoat Horn");
        orderList.add("Mole's Pawbone");
        orderList.add("Orca Tooth");
        orderList.add("Adder Skeleton");
        orderList.add("Ant Chitin");
        orderList.add("Bee Chitin");
        orderList.add("Mammoth Tusk");
        orderList.add("Cave Louse Chitin");
        orderList.add("Crabshell");
        orderList.add("Trollbone");
        orderList.add("Walrus Tusk");
        orderList.add("Troll Tusks");
        orderList.add("Whale Bone Material");
        orderList.add("Wishbone");
    }

    public TransferItems2(NContext context, HashSet<String> items)
    {
        this.cnt = context;
        this.items = items;
    }

    /**
     * Helper class to store item transfer information
     */
    private static class ItemTransfer {
        String itemName;
        double quality;
        String areaId;

        ItemTransfer(String itemName, double quality, String areaId) {
            this.itemName = itemName;
            this.quality = quality;
            this.areaId = areaId;
        }
    }

    /**
     * Helper class to group transfers by quality threshold for proper ordering
     */
    private static class ThresholdGroup {
        double threshold;
        Map<String, List<ItemTransfer>> itemsByArea = new LinkedHashMap<>();

        ThresholdGroup(double threshold) {
            this.threshold = threshold;
        }
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException
    {
        // Step 1: Sort items into priority/non-priority (preserve existing orderList behavior)
        ArrayList<String> before = new ArrayList<>();
        ArrayList<String> after = new ArrayList<>();

        for (String item : items)
        {
            if(orderList.contains(item))
            {
                before.add(item);
            }
            else
            {
                after.add(item);
            }
        }
        ArrayList<String> resitems = new ArrayList<>();
        resitems.addAll(before);
        resitems.addAll(after);

        // Step 2: Group items by quality threshold first, then by area within each threshold
        // This ensures higher quality thresholds are processed first (preventing lower threshold
        // areas from grabbing high quality items)
        TreeMap<Double, ThresholdGroup> thresholdGroups = new TreeMap<>(Collections.reverseOrder());

        for(String item : resitems) {
            TreeMap<Double,String> areas = cnt.getOutAreas(item);
            if(areas != null) {
                for (Double quality : areas.descendingKeySet()) {
                    if (!getItemsExactMatch(item, quality).isEmpty()) {
                        String areaId = areas.get(quality);
                        ThresholdGroup group = thresholdGroups.computeIfAbsent(quality, ThresholdGroup::new);
                        group.itemsByArea.computeIfAbsent(areaId, k -> new ArrayList<>())
                            .add(new ItemTransfer(item, quality, areaId));
                    }
                }
            }
        }

        // Step 3: Process each threshold group in order (highest first)
        for (ThresholdGroup group : thresholdGroups.values()) {

            if (group.threshold > 1) {
                // Items with thresholds: process in arbitrary order (no optimization needed)
                for (String areaId : group.itemsByArea.keySet()) {
                    processAreaTransfers(areaId, group.itemsByArea.get(areaId), gui);
                }
            } else {
                // Items without thresholds (threshold <= 1): use greedy nearest neighbor
                Map<String, List<ItemTransfer>> remaining = new HashMap<>(group.itemsByArea);
                while (!remaining.isEmpty()) {
                    String nearestAreaId = findNearestArea(remaining.keySet(), gui);
                    if (nearestAreaId == null) break;
                    processAreaTransfers(nearestAreaId, remaining.get(nearestAreaId), gui);
                    remaining.remove(nearestAreaId);
                }
            }
        }

        return Results.SUCCESS();
    }


    /**
     * Process all item transfers for a specific area.
     */
    private void processAreaTransfers(String areaId, List<ItemTransfer> itemsForArea, NGameUI gui) throws InterruptedException {
        for (ItemTransfer itemTransfer : itemsForArea) {
            ArrayList<NContext.ObjectStorage> storages = cnt.getOutStorages(itemTransfer.itemName, itemTransfer.quality);
            Container lastContainer = null;
            for (NContext.ObjectStorage output : storages) {
                if (output instanceof NContext.Pile) {
                    new TransferToPiles(cnt.getRCArea(areaId), itemTransfer.itemName,
                        (int)itemTransfer.quality).run(gui);
                }
                if (output instanceof Container) {
                    lastContainer = (Container) output;
                    TreeMap<Double,String> areas = cnt.getOutAreas(itemTransfer.itemName);
                    TransferToContainer ttc = new TransferToContainer((Container) output, itemTransfer.itemName,
                        (int)itemTransfer.quality);
                    ttc.needsSorting = areas != null && areas.size() > 1;
                    ttc.run(gui);
                }
                if (output instanceof NContext.Barrel) {
                    if (getItemsExactMatch(itemTransfer.itemName, itemTransfer.quality).isEmpty())
                        break;
                    new TransferToBarrel(Finder.findGob(((NContext.Barrel) output).barrel),
                        itemTransfer.itemName).run(gui);
                }
                if (output instanceof NContext.Barter) {
                    new TransferToBarter((NContext.Barter) output,
                        new NAlias(itemTransfer.itemName), (int) itemTransfer.quality).run(gui);
                }
            }

            // Every reachable output was tried and copies are still stuck in the inventory -
            // if this area's PUT config for the item allows it, make room in the last container
            // tried by bumping out its lowest-quality copies instead of leaving the newcomers
            // behind. Tetris-shaped containers (drying frames etc.) place by sprite shape, not
            // by quality, so a generic quality-based swap doesn't apply to them.
            if (lastContainer != null && lastContainer.isFull()
                    && lastContainer.getattr(Container.Tetris.class) == null
                    && !getItemsExactMatch(itemTransfer.itemName, itemTransfer.quality).isEmpty()
                    && cnt.isReplaceEnabled(areaId, itemTransfer.itemName)) {
                new ReplaceLowestQuality(lastContainer, itemTransfer.itemName, itemTransfer.quality).run(gui);

                // Replacing doesn't empty the inventory - it still leaves something stuck there:
                // either the copy that lost the swap, or (if nothing in the container was
                // actually worse) the whole newcomer. Give every other PUT zone configured for
                // this item a shot before giving up on it entirely.
                if (!getItemsExactMatch(itemTransfer.itemName, itemTransfer.quality).isEmpty()) {
                    storeInAlternateAreas(itemTransfer.itemName, itemTransfer.quality, areaId, gui);
                }
            }
        }
    }

    /**
     * Last-resort fallback once the nearest zone the normal per-item routing cache already knows
     * about ({@link NContext#getOutAreas}) is full even after a replace: sweeps every other area
     * actually configured to accept {@code item} as a PUT target - farther zones the cache never
     * surfaces because it keeps only the nearest area per quality threshold - nearest first,
     * until either the inventory has nothing left to place or every alternate is exhausted too.
     * Container-only (see {@link NContext#findAlternateOutAreas}); keeps trying until there are
     * no more zones or no more copies of the item to store.
     */
    private void storeInAlternateAreas(String item, double quality, String excludeAreaId, NGameUI gui) throws InterruptedException {
        TreeMap<Double,String> knownAreas = cnt.getOutAreas(item);
        HashSet<String> tried = knownAreas != null ? new HashSet<>(knownAreas.values()) : new HashSet<>();
        tried.add(excludeAreaId);

        for (String areaId : cnt.findAlternateOutAreas(item, tried)) {
            if (getItemsExactMatch(item, quality).isEmpty())
                return;
            storeInArea(areaId, item, quality, gui);
        }
    }

    /**
     * Attempts to place every remaining inventory copy of {@code item} into {@code areaId}'s PUT
     * containers - the single-area equivalent of the main per-item routing loop above, for an
     * ad-hoc area outside the normal cache. Containers only: this alternate area's own stockpiles
     * are left alone, matching the rest of this replace/fallback feature. Respects this area's
     * own configured PUT threshold for the item (which can differ from {@code quality}, the
     * threshold of the zone that was actually full) - never moves in a copy neither threshold
     * would accept.
     */
    private void storeInArea(String areaId, String item, double quality, NGameUI gui) throws InterruptedException {
        double effectiveQuality = Math.max(quality, cnt.getOutThreshold(areaId, item));
        for (NContext.ObjectStorage output : cnt.getOutStoragesForArea(areaId, item)) {
            if (!(output instanceof Container))
                continue;
            if (getItemsExactMatch(item, effectiveQuality).isEmpty())
                return;
            new TransferToContainer((Container) output, item, (int) effectiveQuality).run(gui);
        }
    }

    /**
     * Find the nearest area from a set of area IDs using ChunkNav path cost.
     * Recalculates from current player position for greedy optimization.
     */
    private String findNearestArea(Set<String> areaIds, NGameUI gui) {
        String nearest = null;
        double minDist = Double.MAX_VALUE;

        for (String areaId : areaIds) {
            double dist = cnt.getDistanceToAreaById(areaId, gui);
            if (dist < minDist) {
                minDist = dist;
                nearest = areaId;
            }
        }

        // Fallback if no path found for any area
        if (nearest == null && !areaIds.isEmpty()) {
            nearest = areaIds.iterator().next();
        }

        return nearest;
    }

    /**
     * Gets items from inventory with exact name match only.
     * This prevents substring matching issues where "Straw Hat" would match "Straw" area.
     */
    private static ArrayList<WItem> getItemsExactMatch(String exactName, double quality) throws InterruptedException {
        ArrayList<WItem> allItems = NUtils.getGameUI().getInventory().getItems(new NAlias(exactName), quality);
        ArrayList<WItem> exactMatches = new ArrayList<>();
        for (WItem witem : allItems) {
            if (((NGItem) witem.item).name().equals(exactName)) {
                exactMatches.add(witem);
            }
        }
        return exactMatches;
    }

}
