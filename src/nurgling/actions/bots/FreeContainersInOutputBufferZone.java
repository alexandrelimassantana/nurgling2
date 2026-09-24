package nurgling.actions.bots;

import haven.*;
import nurgling.*;
import nurgling.actions.*;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tools.*;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Unboxes a pre-defined "Output Buffer Zone": drains any containers/stockpiles found there and
 * redistributes their items to the areas actually meant for them, the same way
 * {@link FreeContainersInUnboxZone} does for a plain "unbox" zone.
 * <p>
 * The difference is the target lookup never picks another buffer zone as the destination
 * (see {@link NContext#excludeOutputBufferZones}) - a buffer zone is only ever a temporary
 * drop-off, so redistributing into a second buffer zone would just defer the work instead of
 * doing it.
 */
public class FreeContainersInOutputBufferZone implements Action {

    @Override
    public Results run(NGameUI gui) throws InterruptedException {

        // Find the area with "output buffer" specialization
        NContext context = new NContext(gui);
        context.excludeOutputBufferZones = true;
        NArea bufferArea = context.goToArea(Specialisation.SpecName.outputBuffer);

        if (bufferArea == null) {
            NUtils.getGameUI().error("No output buffer zone area found!");
            return Results.ERROR("No output buffer zone area found");
        }

        Pair<Coord2d,Coord2d> area = bufferArea.getRCArea();
        ArrayList<Container> containers = new ArrayList<>();

        if(area!=null) {
            // Free containers in the area
            for (Gob sm : Finder.findGobs(area, new NAlias(new ArrayList<>(NContext.contcaps.keySet())))) {
                Container cand = new Container(sm, NContext.contcaps.get(sm.ngob.name), bufferArea);
                cand.initattr(Container.Space.class);
                containers.add(cand);
            }
            if (!containers.isEmpty())
                new FreeContainers(containers, true).run(gui);
        }

        ArrayList<Gob> gobs;
        HashSet<String> targets = new HashSet<>();
        while(!(gobs = Finder.findGobs(area, new NAlias("stockpile"))).isEmpty())
        {
            for (Gob pile : gobs) {
                if(PathFinder.isAvailable(pile)) {
                    Coord size = StockpileUtils.itemMaxSize.get(pile.ngob.name);
                    new PathFinder(pile).run(gui);
                    new OpenTargetContainer("Stockpile",pile).run(gui);
                    int target_size = 0;
                    while (Finder.findGob(pile.id) != null)
                        if ( NUtils.getGameUI().getInventory().getNumberFreeCoord((size != null) ?size:new Coord(1,1)) > 0) {
                            NISBox spbox = gui.getStockpile();
                            if (spbox != null) {
                                do {
                                    if (Finder.findGob(pile.id) == null&&target_size!=0) {
                                        break;
                                    }
                                    target_size = NUtils.getGameUI().getInventory().getNumberFreeCoord((size != null) ?size:new Coord(1,1));
                                    if (target_size == 0) {
                                        new FreeInventory2(context).run(gui);
                                        if(Finder.findGob(pile.id)==null && (Boolean) NConfig.get(NConfig.Key.useGlobalPf)) {
                                            context.goToArea(Specialisation.SpecName.outputBuffer);
                                        }
                                        targets.clear();
                                        if (Finder.findGob(pile.id) != null) {
                                            new PathFinder(pile).run(gui);
                                            new OpenTargetContainer("Stockpile", pile).run(gui);
                                        } else break;
                                    } else {
                                        TakeItemsFromPile tifp = new TakeItemsFromPile(pile, spbox, target_size);
                                        tifp.run(gui);
                                        for (NGItem item : tifp.newItems())
                                            targets.add((item).name());
                                    }
                                }
                                while (target_size!=0);
                            }
                        }
                    else
                        {
                            new FreeInventory2(context).run(gui);
                            if(Finder.findGob(pile.id) == null && (Boolean) NConfig.get(NConfig.Key.useGlobalPf)) {
                                context.goToArea(Specialisation.SpecName.outputBuffer);
                            }
                            if(Finder.findGob(pile.id) != null) {
                                new PathFinder(pile).run(gui);
                                new OpenTargetContainer("Stockpile", pile).run(gui);
                            }
                        }
                }
            }
        }
        new FreeInventory2(context).run(gui);
        return Results.SUCCESS();
    }
}
