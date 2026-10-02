package nurgling.actions;

import haven.Gob;
import haven.WItem;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NInventory;
import nurgling.NInventory.QualityType;
import nurgling.tools.Container;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;

import java.util.ArrayList;

/**
 * Run against the last PUT container tried for an item once it turned out to be full, so the
 * item doesn't just get left behind in the inventory. Swaps the container's lowest-quality copies
 * for higher-quality copies still in the inventory, one for one, until either the inventory runs
 * out of eligible copies or every remaining one is no better than the worst copy still stored -
 * so the container always ends up holding the best copies seen.
 * <p>
 * Only runs when enabled per item via the area's PUT drag-n-drop config (see
 * {@link nurgling.widgets.IngredientContainer#setReplace}). Respects that same PUT zone's own
 * quality threshold: an inventory copy below it is never a swap-in candidate, even if it beats
 * what's currently stored - this never moves a copy into a container the zone's own rules
 * wouldn't otherwise have let it accept.
 */
public class ReplaceLowestQuality implements Action
{
    final Container container;
    final String itemName;
    final double minQuality;

    public ReplaceLowestQuality(Container container, String itemName, double minQuality)
    {
        this.container = container;
        this.itemName = itemName;
        this.minQuality = minQuality;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException
    {
        Gob gcont = Finder.findGob(container.gobid);
        if (gcont == null)
            return Results.FAIL();

        PathFinder pf = new PathFinder(gcont);
        pf.isHardMode = true;
        pf.run(gui);

        if (container.cap != null)
        {
            new OpenTargetContainer(container.cap, gcont).run(gui);
        }

        NInventory targetInv = gui.getInventory(container.cap);
        if (targetInv == null)
            return Results.FAIL();

        while (true)
        {
            ArrayList<WItem> mine = eligibleMatches(gui.getInventory().getItems(new NAlias(itemName), QualityType.High));
            if (mine.isEmpty())
                break;

            ArrayList<WItem> stored = exactMatches(targetInv.getItems(new NAlias(itemName), QualityType.Low));
            if (stored.isEmpty())
                break;

            WItem best = mine.get(0);
            WItem worst = stored.get(0);

            Float bestQ = ((NGItem) best.item).quality;
            Float worstQ = ((NGItem) worst.item).quality;
            if (bestQ == null || worstQ == null || bestQ <= worstQ)
                break;

            if (TransferToContainer.transfer(worst, gui.getInventory(), 1) == 0)
                break;
            if (TransferToContainer.transfer(best, targetInv, 1) == 0)
                break;
        }

        container.update();
        return Results.SUCCESS();
    }

    private ArrayList<WItem> exactMatches(ArrayList<WItem> items)
    {
        ArrayList<WItem> result = new ArrayList<>();
        for (WItem witem : items)
        {
            if (NGItem.validateItem(witem) && ((NGItem) witem.item).name().equals(itemName))
                result.add(witem);
        }
        return result;
    }

    /** Exact name matches that also meet this zone's own PUT quality threshold, if any. */
    private ArrayList<WItem> eligibleMatches(ArrayList<WItem> items)
    {
        ArrayList<WItem> result = new ArrayList<>();
        for (WItem witem : exactMatches(items))
        {
            Float quality = ((NGItem) witem.item).quality;
            if (minQuality <= 1 || (quality != null && quality >= minQuality))
                result.add(witem);
        }
        return result;
    }
}
