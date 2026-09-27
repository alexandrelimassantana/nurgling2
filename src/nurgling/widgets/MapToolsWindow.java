package nurgling.widgets;

import haven.*;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.conf.ProspectKind;
import nurgling.conf.ProspectMarkSettings;
import nurgling.i18n.L10n;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Single home for everything that controls what the map draws: tree/fish icon toggles,
 * the prospected-sample layer with an independent quality threshold per resource kind,
 * and the terrain/ore tile search.
 *
 * Opened from the gear button on the map window. All state lives in NConfig, so the
 * toolbar toggle buttons and these controls are two views of the same values.
 */
public class MapToolsWindow extends Window {
    private static final int MARGIN = UI.scale(5);
    private static final int OVERLAY_W = UI.scale(300);
    private static final int ROW_GAP = UI.scale(3);
    private static final int TAB_BTN_W = UI.scale(90);
    private static final int ENTRY_W = UI.scale(44);
    private static final int ENTRY_X = OVERLAY_W - UI.scale(102);
    private static final int COUNT_X = OVERLAY_W - UI.scale(50);
    private static final int COUNT_W = OVERLAY_W - COUNT_X;
    private static final int SEARCH_BTN_W = UI.scale(70);
    private static final double COUNT_INTERVAL = 0.5;

    private final List<KindRow> rows = new ArrayList<>();
    private final List<GroupRow> groupRows = new ArrayList<>();
    private final List<NameRow> nameRows = new ArrayList<>();
    private Label nameFilterLabel;
    private Set<String> shownGroups = new HashSet<>();
    private Set<String> shownNames = new HashSet<>();
    private Widget overlaysTab;
    private int qualityHunterGroupsY;
    private final Tabs tabs;
    private final Tabs.Tab searchTab;
    private final TerrainSearchPanel terrainSearchPanel;
    private TextEntry masterEntry;
    private double countTimer = COUNT_INTERVAL;

    public MapToolsWindow() {
        super(new Coord(OVERLAY_W, UI.scale(260)), L10n.get("maptools.title"), true);

        tabs = new Tabs(Coord.z, Coord.z, this) {
            @Override
            public void changed(Tab from, Tab to) {
                /* The two tabs are very different sizes; follow the visible one. */
                MapToolsWindow.this.pack();
            }
        };
        Tabs.Tab overlays = tabs.add();
        searchTab = tabs.add();

        buildOverlays(overlays);
        refreshQualityHunterFilters();
        terrainSearchPanel = searchTab.add(new TerrainSearchPanel(), 0, 0);

        Widget tabBtn = add(tabs.new TabButton(TAB_BTN_W, L10n.get("maptools.tab_overlays"), overlays), 0, 0);
        add(tabs.new TabButton(TAB_BTN_W, L10n.get("maptools.tab_search"), searchTab), TAB_BTN_W + MARGIN, 0);

        /* Place the tab bodies under the buttons, whatever height the buttons turned out to be. */
        tabs.c = new Coord(0, tabBtn.sz.y + MARGIN);
        overlays.c = tabs.c;
        searchTab.c = tabs.c;

        tabs.showtab(overlays);
        pack();
    }

    private void buildOverlays(Widget tab) {
        overlaysTab = tab;
        int y = 0;

        tab.add(new Label(L10n.get("maptools.section_icons")), 0, y);
        y += UI.scale(17);

        y = addIconRow(tab, y, L10n.get("maptools.tree_icons"),
                () -> NMiniMap.showTreeIcons(), val -> NMiniMap.showTreeIcons(val), MapToolsWindow::openTreeSearch);
        y = addIconRow(tab, y, L10n.get("maptools.fish_icons"),
                () -> NMiniMap.showFishIcons(), val -> NMiniMap.showFishIcons(val), MapToolsWindow::openFishSearch);
        y = addForageRows(tab, y);
        CheckBox cluster = tab.add(new CheckBox(L10n.get("maptools.cluster_marks")), UI.scale(4), y);
        cluster.settip(L10n.get("maptools.cluster_marks_tip"));
        cluster.state(() -> NMiniMap.clusterMinedMarks());
        cluster.set(val -> NMiniMap.clusterMinedMarks(val));
        y += cluster.sz.y + ROW_GAP;

        y += MARGIN;
        Label samplesLbl = tab.add(new Label(L10n.get("maptools.section_samples")), 0, y);
        Button minedSearch = tab.add(new Button(SEARCH_BTN_W, L10n.get("maptools.search_btn")) {
            @Override
            public void click() {
                openMineralSearch(null);
            }
        }, OVERLAY_W - SEARCH_BTN_W, y);
        minedSearch.settip(L10n.get("mineral.search_tip"));
        y += alignRow(y, samplesLbl, minedSearch) + ROW_GAP;

        // Master row: hides the whole layer without losing the per-kind settings.
        CheckBox master = tab.add(new CheckBox(L10n.get("maptools.show_samples")), UI.scale(4), y);
        master.state(() -> settings().master);
        master.set(val -> {
            settings().master = val;
            store();
        });
        Label masterLbl = tab.add(new Label(L10n.get("maptools.threshold")), ENTRY_X - UI.scale(26), y);
        masterEntry = tab.add(new TextEntry(ENTRY_W, "0") {
            @Override
            public boolean keydown(KeyDownEvent ev) {
                if(ev.code == java.awt.event.KeyEvent.VK_ENTER) {
                    applyToAll();
                    return true;
                }
                return super.keydown(ev);
            }
        }, ENTRY_X, y);
        Button setAll = tab.add(new Button(COUNT_W, L10n.get("maptools.set_all")) {
            @Override
            public void click() {
                applyToAll();
            }
        }, COUNT_X, y);
        y += alignRow(y, master, masterLbl, masterEntry, setAll) + ROW_GAP;

        for(ProspectKind kind : ProspectKind.values()) {
            // Quality Hunter marks are arbitrary, player-configured items, not a fixed
            // resource kind -- they get their own dedicated search (below) instead of
            // sharing this per-kind visibility/threshold list.
            if(kind == ProspectKind.QUALITY_HUNTER)
                continue;
            KindRow row = new KindRow(tab, kind, y);
            rows.add(row);
            y += row.height;
        }

        y += MARGIN;
        Label qualityHunterLbl = tab.add(new Label(L10n.get("maptools.section_quality_hunter")), 0, y);
        Button qualityHunterSearch = tab.add(new Button(SEARCH_BTN_W, L10n.get("maptools.search_btn")) {
            @Override
            public void click() {
                openQualityHunterSearch();
            }
        }, OVERLAY_W - SEARCH_BTN_W, y);
        qualityHunterSearch.settip(L10n.get("quality_hunter.search_tip"));
        y += alignRow(y, qualityHunterLbl, qualityHunterSearch) + ROW_GAP;

        qualityHunterGroupsY = y;
        tab.pack();
    }

    /** One Quality Hunter group: visibility toggle and a live shown/total count, mirroring
     *  KindRow but keyed by a player-chosen group name instead of a fixed ProspectKind. A mark can
     *  belong to several groups at once, so its count is tallied under each one. */
    private class GroupRow {
        private final String group;
        private final CheckBox box;
        private final Label count;
        private final int height;

        GroupRow(Widget tab, String group, int y) {
            this.group = group;
            box = tab.add(new CheckBox(group.isEmpty() ? L10n.get("quality_hunter.ungrouped") : group), UI.scale(14), y);
            box.state(() -> QualityHunterContainer.isGroupVisible(group));
            box.set(val -> QualityHunterContainer.setGroupVisible(group, val));
            count = tab.add(new Label("-"), COUNT_X, y);
            height = alignRow(y, box, count) + ROW_GAP;
        }

        void setCount(int shown, int total) {
            count.settext((total == 0) ? "-" : (shown + "/" + total));
        }
    }

    /** One tracked item name: visibility toggle and a live shown/total count -- the "or by name"
     *  half of Quality Hunter's filtering, independent of and combined (AND) with group visibility. */
    private class NameRow {
        private final String name;
        private final CheckBox box;
        private final Label count;
        private final int height;

        NameRow(Widget tab, String name, int y) {
            this.name = name;
            box = tab.add(new CheckBox(name), UI.scale(14), y);
            box.state(() -> QualityHunterContainer.isNameVisible(name));
            box.set(val -> QualityHunterContainer.setNameVisible(name, val));
            count = tab.add(new Label("-"), COUNT_X, y);
            height = alignRow(y, box, count) + ROW_GAP;
        }

        void setCount(int shown, int total) {
            count.settext((total == 0) ? "-" : (shown + "/" + total));
        }
    }

    /** Every group, and every resource name, currently recorded on a Quality Hunter mark, anywhere
     *  -- not the tracked-item config, which can list a group nothing has been found for yet, or
     *  drop one still on old marks. Filtering is about what marks exist, so it follows the marks. */
    private void currentQualityHunterFilters(NGameUI gui, Set<String> groupsOut, Set<String> namesOut) {
        if(gui == null || gui.labeledMarkService == null)
            return;
        for(LabeledMinimapMark mark : gui.labeledMarkService.getAllMarks()) {
            if(mark.kind != ProspectKind.QUALITY_HUNTER)
                continue;
            namesOut.add(mark.resourceType);
            if(mark.groups.isEmpty())
                groupsOut.add("");
            else
                groupsOut.addAll(mark.groups);
        }
    }

    /** Rebuilds the Quality Hunter group/name rows only if the sets actually changed, so an open
     *  panel doesn't lose scroll position or flicker on every count poll. */
    private void refreshQualityHunterFilters() {
        Set<String> groups = new TreeSet<>();
        Set<String> names = new TreeSet<>();
        currentQualityHunterFilters(NUtils.getGameUI(), groups, names);
        if(groups.equals(shownGroups) && names.equals(shownNames))
            return;
        shownGroups = groups;
        shownNames = names;

        for(GroupRow row : groupRows) {
            row.box.destroy();
            row.count.destroy();
        }
        groupRows.clear();
        for(NameRow row : nameRows) {
            row.box.destroy();
            row.count.destroy();
        }
        nameRows.clear();
        if(nameFilterLabel != null) {
            nameFilterLabel.destroy();
            nameFilterLabel = null;
        }

        int y = qualityHunterGroupsY;
        for(String group : groups) {
            GroupRow row = new GroupRow(overlaysTab, group, y);
            groupRows.add(row);
            y += row.height;
        }
        if(!names.isEmpty()) {
            y += MARGIN;
            nameFilterLabel = overlaysTab.add(new Label(L10n.get("quality_hunter.filter_by_name")), 0, y);
            y += UI.scale(17);
            for(String name : names) {
                NameRow row = new NameRow(overlaysTab, name, y);
                nameRows.add(row);
                y += row.height;
            }
        }
        overlaysTab.pack();
        pack();
    }

    private int addIconRow(Widget tab, int y, String label, java.util.function.Supplier<Boolean> state,
                           java.util.function.Consumer<Boolean> set, Runnable search) {
        CheckBox box = tab.add(new CheckBox(label), UI.scale(4), y);
        box.state(state);
        box.set(set);
        Button btn = tab.add(new Button(SEARCH_BTN_W, L10n.get("maptools.search_btn")) {
            @Override
            public void click() {
                search.run();
            }
        }, OVERLAY_W - SEARCH_BTN_W, y);
        return y + alignRow(y, box, btn) + ROW_GAP;
    }

    /** Forage finds: show toggle with its own quality threshold and search, then the recording switch. */
    private int addForageRows(Widget tab, int y) {
        CheckBox box = tab.add(new CheckBox(L10n.get("maptools.forage_icons")), UI.scale(4), y);
        box.state(() -> NMiniMap.showForageFinds());
        box.set(val -> NMiniMap.showForageFinds(val));
        Label lbl = tab.add(new Label(L10n.get("maptools.threshold")), ENTRY_X - UI.scale(26), y);
        TextEntry entry = tab.add(new TextEntry(ENTRY_W, String.valueOf(NMiniMap.forageMinQuality())) {
            @Override
            public void changed() {
                super.changed();
                Integer val = parseThreshold(text());
                if(val != null)
                    NMiniMap.forageMinQuality(val);
            }
        }, ENTRY_X, y);
        entry.settip(L10n.get("maptools.forage_min_tip"));
        Button btn = tab.add(new Button(SEARCH_BTN_W, L10n.get("maptools.search_btn")) {
            @Override
            public void click() {
                openForageSearch();
            }
        }, OVERLAY_W - SEARCH_BTN_W, y);
        btn.settip(L10n.get("forage.search_tip"));
        y += alignRow(y, box, lbl, entry, btn) + ROW_GAP;

        CheckBox record = tab.add(new CheckBox(L10n.get("maptools.forage_record")), UI.scale(14), y);
        record.settip(L10n.get("maptools.forage_record_tip"));
        record.state(nurgling.forage.ForageRecorder::enabled);
        record.set(nurgling.forage.ForageRecorder::enabled);
        return y + record.sz.y + ROW_GAP;
    }

    /**
     * Vertically centre a row of widgets against the tallest one and report its height.
     * Buttons, checkboxes and text entries all have image-derived heights, so a hardcoded
     * row height either overlaps them or leaves a gap.
     */
    private static int alignRow(int y, Widget... widgets) {
        int height = 0;
        for(Widget widget : widgets)
            height = Math.max(height, widget.sz.y);
        for(Widget widget : widgets)
            widget.c = new Coord(widget.c.x, y + ((height - widget.sz.y) / 2));
        return height;
    }

    /** One resource kind: enable flag, its own quality threshold, and a live shown/total count. */
    private class KindRow {
        private final ProspectKind kind;
        private final TextEntry entry;
        private final Label count;
        private final int height;

        KindRow(Widget tab, ProspectKind kind, int y) {
            this.kind = kind;
            CheckBox box = tab.add(new CheckBox(L10n.get(kind.l10nKey)), UI.scale(14), y);
            box.state(() -> settings().enabled(kind));
            box.set(val -> {
                settings().setEnabled(kind, val);
                store();
            });
            entry = tab.add(new TextEntry(ENTRY_W, String.valueOf(settings().threshold(kind))) {
                @Override
                public void changed() {
                    super.changed();
                    Integer val = parseThreshold(text());
                    if(val != null) {
                        settings().setThreshold(kind, val);
                        store();
                    }
                }

                @Override
                public boolean keydown(KeyDownEvent ev) {
                    if(ev.code == java.awt.event.KeyEvent.VK_ENTER) {
                        sync();
                        return true;
                    }
                    return super.keydown(ev);
                }
            }, ENTRY_X, y);
            count = tab.add(new Label("-"), COUNT_X, y);
            height = alignRow(y, box, entry, count) + ROW_GAP;
        }

        /** Rewrite the field from the stored (clamped) value. */
        void sync() {
            String val = String.valueOf(settings().threshold(kind));
            if(!val.equals(entry.text()))
                entry.settext(val);
        }

        void setCount(int shown, int total) {
            count.settext((total == 0) ? "-" : (shown + "/" + total));
        }
    }

    private void applyToAll() {
        Integer val = parseThreshold(masterEntry.text());
        if(val == null)
            return;
        ProspectMarkSettings settings = settings();
        settings.setAllThresholds(val);
        store();
        masterEntry.settext(String.valueOf(ProspectMarkSettings.clamp(val)));
        for(KindRow row : rows)
            row.sync();
    }

    /** Lenient parse: blank counts as 0, anything unparseable leaves the stored value alone. */
    private static Integer parseThreshold(String text) {
        String trimmed = (text == null) ? "" : text.trim();
        if(trimmed.isEmpty())
            return 0;
        try {
            return ProspectMarkSettings.clamp(Integer.parseInt(trimmed));
        } catch(NumberFormatException e) {
            return null;
        }
    }

    private ProspectMarkSettings settings() {
        ProspectMarkSettings settings = NMiniMap.prospectSettings();
        if(settings == null) {
            settings = new ProspectMarkSettings();
            NConfig.set(NConfig.Key.prospectMarks, settings);
        }
        return settings;
    }

    /** The settings object is mutated in place; re-setting it flags the config as dirty. */
    private void store() {
        NConfig.set(NConfig.Key.prospectMarks, settings());
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        if(!visible())
            return;
        countTimer += dt;
        if(countTimer < COUNT_INTERVAL)
            return;
        countTimer = 0;
        updateCounts();
    }

    private void updateCounts() {
        refreshQualityHunterFilters();

        Map<ProspectKind, int[]> tally = new EnumMap<>(ProspectKind.class);
        Map<String, int[]> groupTally = new HashMap<>();
        Map<String, int[]> nameTally = new HashMap<>();
        NGameUI gui = NUtils.getGameUI();
        if(gui != null && gui.labeledMarkService != null && gui.mmap != null && gui.mmap.sessloc != null) {
            for(LabeledMinimapMark mark : gui.labeledMarkService.getMarksForSegment(gui.mmap.sessloc.seg.id)) {
                int[] counts = tally.computeIfAbsent(mark.kind, k -> new int[2]);
                boolean visible = NMiniMap.markVisible(mark);
                counts[1]++;
                if(visible)
                    counts[0]++;
                if(mark.kind == ProspectKind.QUALITY_HUNTER) {
                    int[] ncounts = nameTally.computeIfAbsent(mark.resourceType, k -> new int[2]);
                    ncounts[1]++;
                    if(visible)
                        ncounts[0]++;
                    // A mark in several groups at once is tallied under each -- it genuinely
                    // belongs to all of them, same as its OR visibility rule treats them.
                    List<String> markGroups = mark.groups.isEmpty() ? Collections.singletonList("") : mark.groups;
                    for(String group : markGroups) {
                        int[] gcounts = groupTally.computeIfAbsent(group, k -> new int[2]);
                        gcounts[1]++;
                        if(visible)
                            gcounts[0]++;
                    }
                }
            }
        }
        for(KindRow row : rows) {
            int[] counts = tally.get(row.kind);
            if(counts == null)
                row.setCount(0, 0);
            else
                row.setCount(counts[0], counts[1]);
        }
        for(GroupRow row : groupRows) {
            int[] counts = groupTally.get(row.group);
            if(counts == null)
                row.setCount(0, 0);
            else
                row.setCount(counts[0], counts[1]);
        }
        for(NameRow row : nameRows) {
            int[] counts = nameTally.get(row.name);
            if(counts == null)
                row.setCount(0, 0);
            else
                row.setCount(counts[0], counts[1]);
        }
    }

    @Override
    public void show() {
        // Groups/names accumulate while the window is closed, so rebuild what is on offer.
        refreshQualityHunterFilters();
        super.show();
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if(msg.equals("close")) {
            hide();
        } else {
            super.wdgmsg(sender, msg, args);
        }
    }

    /** Toggle the panel, creating it on first use. */
    public static void toggle() {
        NGameUI gui = NUtils.getGameUI();
        if(gui == null)
            return;
        if(gui.mapToolsWindow != null) {
            if(gui.mapToolsWindow.visible()) {
                gui.mapToolsWindow.hide();
            } else {
                gui.mapToolsWindow.show();
                gui.mapToolsWindow.raise();
            }
        } else {
            gui.mapToolsWindow = new MapToolsWindow();
            gui.add(gui.mapToolsWindow, new Coord(100, 100));
            gui.mapToolsWindow.show();
        }
    }

    /** Open the terrain controls and replace their filter with these gathering biomes. */
    public static void openTerrainSearch(Collection<String> terrains) {
        MapToolsWindow wnd = showSearchTab();
        if(wnd != null) {
            wnd.terrainSearchPanel.selectTerrains(terrains);
            wnd.pack();
        }
    }

    /** Open the terrain controls and highlight exact tile resources (used for quest rocks). */
    public static void openTerrainResources(Collection<String> resources) {
        MapToolsWindow wnd = showSearchTab();
        if(wnd != null) {
            wnd.terrainSearchPanel.selectResources(resources);
            wnd.pack();
        }
    }

    private static MapToolsWindow showSearchTab() {
        NGameUI gui = NUtils.getGameUI();
        if(gui == null)
            return null;
        if(gui.mapToolsWindow == null) {
            gui.mapToolsWindow = new MapToolsWindow();
            gui.add(gui.mapToolsWindow, new Coord(100, 100));
        }
        gui.mapToolsWindow.show();
        gui.mapToolsWindow.raise();
        gui.mapToolsWindow.tabs.showtab(gui.mapToolsWindow.searchTab);
        return gui.mapToolsWindow;
    }

    public static void openTreeSearch() {
        NGameUI gui = NUtils.getGameUI();
        if(gui == null)
            return;
        if(gui.treeSearchWindow != null) {
            if(gui.treeSearchWindow.visible()) {
                gui.treeSearchWindow.hide();
            } else {
                gui.treeSearchWindow.show();
                gui.treeSearchWindow.raise();
            }
        } else {
            gui.treeSearchWindow = new TreeSearchWindow(gui);
            gui.add(gui.treeSearchWindow, new Coord(100, 100));
            gui.treeSearchWindow.show();
        }
    }

    /**
     * Open the ore/gemstone/stone search, preselecting one category.
     *
     * @param preset category to start on, or null for all three
     */
    public static void openMineralSearch(ProspectKind preset) {
        NGameUI gui = NUtils.getGameUI();
        if(gui == null)
            return;
        if(gui.mineralSearchWindow != null) {
            /* Reopen on the asked-for category even when it is already up, so right-clicking
             * the gem button while an ore search is showing does what it looks like. */
            if(gui.mineralSearchWindow.visible() && preset == null) {
                gui.mineralSearchWindow.hide();
                return;
            }
            gui.mineralSearchWindow.show();
            gui.mineralSearchWindow.raise();
            gui.mineralSearchWindow.preset(preset);
            return;
        }
        gui.mineralSearchWindow = new MineralSearchWindow(gui);
        gui.add(gui.mineralSearchWindow, new Coord(100, 100));
        gui.mineralSearchWindow.show();
        gui.mineralSearchWindow.preset(preset);
    }

    public static void openForageSearch() {
        NGameUI gui = NUtils.getGameUI();
        if(gui == null)
            return;
        if(gui.forageSearchWindow != null) {
            if(gui.forageSearchWindow.visible()) {
                gui.forageSearchWindow.hide();
            } else {
                gui.forageSearchWindow.show();
                gui.forageSearchWindow.raise();
            }
        } else {
            gui.forageSearchWindow = new ForageSearchWindow(gui);
            gui.add(gui.forageSearchWindow, new Coord(100, 100));
            gui.forageSearchWindow.show();
        }
    }

    public static void openFishSearch() {
        NGameUI gui = NUtils.getGameUI();
        if(gui == null)
            return;
        if(gui.fishSearchWindow != null) {
            if(gui.fishSearchWindow.visible()) {
                gui.fishSearchWindow.hide();
            } else {
                gui.fishSearchWindow.show();
                gui.fishSearchWindow.raise();
            }
        } else {
            gui.fishSearchWindow = new FishSearchWindow(gui);
            gui.add(gui.fishSearchWindow, new Coord(100, 100));
            gui.fishSearchWindow.show();
        }
    }

    public static void openQualityHunterSearch() {
        NGameUI gui = NUtils.getGameUI();
        if(gui == null)
            return;
        if(gui.qualityHunterSearchWindow != null) {
            if(gui.qualityHunterSearchWindow.visible()) {
                gui.qualityHunterSearchWindow.hide();
            } else {
                gui.qualityHunterSearchWindow.show();
                gui.qualityHunterSearchWindow.raise();
            }
        } else {
            gui.qualityHunterSearchWindow = new QualityHunterSearchWindow(gui);
            gui.add(gui.qualityHunterSearchWindow, new Coord(100, 100));
            gui.qualityHunterSearchWindow.show();
        }
    }
}
