package nurgling.widgets.nsettings;

import haven.Coord;
import haven.Label;
import haven.TextEntry;
import haven.UI;
import nurgling.NConfig;
import nurgling.i18n.L10n;
import nurgling.widgets.ForageTrackerContainer;

public class ForageTrackerSettings extends Panel {

    private static final int DEFAULT_RADIUS = 100;

    ForageTrackerContainer ftc;
    private TextEntry radiusEntry;

    public ForageTrackerSettings() {
        super(L10n.get("forage_tracker.title"));
        int margin = UI.scale(5);

        add(new Label(L10n.get("forage_tracker.radius")), new Coord(margin, 30));
        radiusEntry = add(new TextEntry(UI.scale(60), String.valueOf(DEFAULT_RADIUS)), new Coord(margin + UI.scale(150), 28));

        add(ftc = new ForageTrackerContainer(), UI.scale(margin, 60));
    }

    @Override
    public void load() {
        radiusEntry.settext(String.valueOf(getConfigInt(NConfig.Key.forageTrackerMarkRadius, DEFAULT_RADIUS)));
        ftc.load();
    }

    @Override
    public void save() {
        NConfig.set(NConfig.Key.forageTrackerMarkRadius, parseIntSafe(radiusEntry.text(), getConfigInt(NConfig.Key.forageTrackerMarkRadius, DEFAULT_RADIUS)));
        // Store a snapshot, never the panel's live array: see Dropper.save()
        // for why sharing it with NConfig would wipe the saved list on the
        // next time this panel is opened.
        NConfig.set(NConfig.Key.forageTrackerConf, ftc.getJsonCopy());
        ForageTrackerContainer.invalidateCache();
    }

    private int getConfigInt(NConfig.Key key, int defaultValue) {
        Object val = NConfig.get(key);
        return (val instanceof Number) ? ((Number) val).intValue() : defaultValue;
    }

    private int parseIntSafe(String text, int defaultValue) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

}
