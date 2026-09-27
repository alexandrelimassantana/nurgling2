package nurgling.widgets;

import haven.*;
import haven.Locked;
import nurgling.NGameUI;
import nurgling.conf.ProspectKind;
import nurgling.i18n.L10n;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Search Quality Hunter's marks, kept separate from the general prospect-sample
 * search/filter (ore/gem/stone/water/clay/...): tracked items are arbitrary and
 * player-configured, not a fixed set of resource kinds, so they get their own window
 * with an item-name filter instead of sharing the per-kind category system.
 *
 * <p>Same shape as {@link MineralSearchWindow}: it operates directly on
 * {@link LabeledMinimapMark}, since these marks already carry the exact quality they
 * were recorded at.
 */
public class QualityHunterSearchWindow extends Window {
    private static final int WINDOW_WIDTH = UI.scale(400);
    private static final int WINDOW_HEIGHT = UI.scale(500);
    private static final String ANY = "Any";

    private final NGameUI gui;

    private NDropbox<String> groupDropdown;
    private NDropbox<String> nameDropdown;
    private TextEntry minQualityEntry;
    private ResultsList resultsList;

    private List<String> groups;
    private List<String> names;
    private final int controlX;

    public QualityHunterSearchWindow(NGameUI gui) {
        super(new Coord(WINDOW_WIDTH, WINDOW_HEIGHT), L10n.get("quality_hunter.search_title"), true);
        this.gui = gui;

        int y = UI.scale(10);
        int labelX = UI.scale(10);
        controlX = UI.scale(120);
        int lineHeight = UI.scale(30);

        add(new Label(L10n.get("quality_hunter.group")), labelX, y + UI.scale(5));
        refreshGroupDropdown(y);
        y += lineHeight;

        add(new Label(L10n.get("quality_hunter.item_name")), labelX, y + UI.scale(5));
        refreshNameDropdown(y);
        y += lineHeight;

        add(new Label(L10n.get("quality_hunter.min_quality")), labelX, y + UI.scale(5));
        minQualityEntry = add(new TextEntry(UI.scale(100), "0") {
            @Override
            public boolean keydown(KeyDownEvent ev) {
                if (ev.code == java.awt.event.KeyEvent.VK_ENTER) {
                    performSearch();
                    return true;
                }
                return super.keydown(ev);
            }
        }, controlX, y);
        y += lineHeight;

        add(new Button(UI.scale(150), L10n.get("common.search")) {
            @Override
            public void click() {
                performSearch();
            }
        }, UI.scale(125), y);
        y += lineHeight + UI.scale(10);

        add(new Label(L10n.get("common.results")), labelX, y);
        y += UI.scale(25);

        Coord resultsSize = new Coord(WINDOW_WIDTH - UI.scale(20), WINDOW_HEIGHT - y - UI.scale(10));
        resultsList = add(new ResultsList(resultsSize), labelX, y);

        pack();
    }

    private List<LabeledMinimapMark> trackedMarks() {
        if (gui == null || gui.labeledMarkService == null) {
            return new ArrayList<>();
        }
        return gui.labeledMarkService.getAllMarks().stream()
                .filter(mark -> mark.kind == ProspectKind.QUALITY_HUNTER)
                .collect(Collectors.toList());
    }

    private void refreshGroupDropdown(int y) {
        String previous = (groupDropdown == null) ? ANY : groupDropdown.sel;
        if (groupDropdown != null) {
            if (ui != null) {
                ui.destroy(groupDropdown);
            } else {
                groupDropdown.destroy();
            }
            groupDropdown = null;
        }
        groups = trackedMarks().stream()
                .flatMap(mark -> mark.groups.isEmpty() ? java.util.stream.Stream.of("") : mark.groups.stream())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        groups.add(0, ANY);

        groupDropdown = add(new NDropbox<String>(UI.scale(250), Math.min(groups.size(), 10), UI.scale(20)) {
            @Override
            protected String listitem(int i) {
                return groups.get(i);
            }

            @Override
            protected int listitems() {
                return groups.size();
            }

            @Override
            protected void drawitem(GOut g, String item, int i) {
                g.text(item.isEmpty() ? L10n.get("quality_hunter.ungrouped") : item, Coord.z);
            }
        }, controlX, y);
        groupDropdown.change(groups.contains(previous) ? previous : ANY);
    }

    private void refreshNameDropdown(int y) {
        String previous = (nameDropdown == null) ? ANY : nameDropdown.sel;
        if (nameDropdown != null) {
            // ui is null until this window joins the tree; destroy() itself is null-safe.
            if (ui != null) {
                ui.destroy(nameDropdown);
            } else {
                nameDropdown.destroy();
            }
            nameDropdown = null;
        }
        names = trackedMarks().stream()
                .map(mark -> mark.resourceType)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        names.add(0, ANY);

        nameDropdown = add(new NDropbox<String>(UI.scale(250), Math.min(names.size(), 10), UI.scale(20)) {
            @Override
            protected String listitem(int i) {
                return names.get(i);
            }

            @Override
            protected int listitems() {
                return names.size();
            }

            @Override
            protected void drawitem(GOut g, String item, int i) {
                g.text(item, Coord.z);
            }
        }, controlX, y);
        nameDropdown.change(names.contains(previous) ? previous : ANY);
    }

    private void performSearch() {
        double minQuality;
        try {
            String txt = minQualityEntry.text().trim();
            minQuality = txt.isEmpty() ? 0 : Double.parseDouble(txt.replace(',', '.'));
        } catch (NumberFormatException e) {
            minQuality = 0;
        }
        final double min = minQuality;
        final String name = nameDropdown.sel;
        final String group = groupDropdown.sel;

        List<LabeledMinimapMark> results = trackedMarks().stream()
                .filter(mark -> ANY.equals(name) || name.equals(mark.resourceType))
                .filter(mark -> ANY.equals(group) || (group.isEmpty() ? mark.groups.isEmpty() : mark.groups.contains(group)))
                .filter(mark -> mark.quality >= min)
                // Richest first: the whole point of recording quality is finding the best spot.
                .sorted(Comparator.comparingDouble((LabeledMinimapMark m) -> m.quality).reversed())
                .collect(Collectors.toList());

        resultsList.setResults(results);
    }

    private class ResultsList extends SListBox<LabeledMinimapMark, Widget> {
        private List<LabeledMinimapMark> results = new ArrayList<>();

        ResultsList(Coord sz) {
            super(sz, UI.scale(25));
        }

        void setResults(List<LabeledMinimapMark> results) {
            this.results = results;
        }

        @Override
        protected List<LabeledMinimapMark> items() {
            return results;
        }

        @Override
        protected Widget makeitem(LabeledMinimapMark mark, int idx, Coord sz) {
            return new ItemWidget<LabeledMinimapMark>(this, sz, mark) {
                {
                    int deleteButtonWidth = UI.scale(22);
                    int panButtonWidth = sz.x - deleteButtonWidth - UI.scale(4);

                    add(new Button(panButtonWidth, "") {
                        @Override
                        public void draw(GOut g) {
                            g.text(String.format("%s - q%.0f", mark.resourceType, mark.quality), Coord.z);
                        }

                        @Override
                        public void click() {
                            panMapToMark(mark);
                        }
                    }, Coord.z);

                    add(new IButton(nurgling.NStyle.crossSquare[0].back,
                                    nurgling.NStyle.crossSquare[1].back,
                                    nurgling.NStyle.crossSquare[2].back) {
                        @Override
                        public void click() {
                            if (gui != null && gui.labeledMarkService != null) {
                                gui.labeledMarkService.removeMark(mark.getLocationId());
                                gui.msg("Removed " + mark.resourceType + " mark", java.awt.Color.YELLOW);
                                performSearch();
                            }
                        }
                    }, new Coord(panButtonWidth + UI.scale(2), (sz.y - UI.scale(22)) / 2));
                }
            };
        }
    }

    private void panMapToMark(LabeledMinimapMark mark) {
        if (gui == null || gui.mapfile == null) {
            return;
        }
        NMapWnd mapWnd = gui.mapfile;
        if (mapWnd.view == null) {
            return;
        }
        if (!mapWnd.visible()) {
            gui.togglewnd(mapWnd);
        }
        if (gui.mmap == null || gui.mmap.file == null) {
            return;
        }
        try (Locked lk = new Locked(gui.mmap.file.lock.readLock())) {
            MapFile.Segment segment = gui.mmap.file.segments.get(mark.segmentId);
            if (segment == null) {
                gui.msg("That mark is in a different area", java.awt.Color.YELLOW);
                return;
            }
            mapWnd.view.center(new MiniMap.Location(segment, mark.tileCoords));
            mapWnd.view.follow(null);
            gui.msg("Map centered on " + mark.resourceType, java.awt.Color.GREEN);
        }
    }

    @Override
    public void show() {
        // Marks accumulate while the window is closed, so rebuild what is on offer.
        refreshGroupDropdown(groupDropdown.c.y);
        refreshNameDropdown(nameDropdown.c.y);
        performSearch();
        super.show();
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if (msg.equals("close")) {
            hide();
        } else {
            super.wdgmsg(sender, msg, args);
        }
    }
}
