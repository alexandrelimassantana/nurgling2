package nurgling.widgets.nsettings;

import haven.*;
import nurgling.NConfig;
import nurgling.NUtils;
import nurgling.i18n.L10n;
import nurgling.widgets.QualityHunterContainer;
import nurgling.widgets.QualityHunterProfiles;
import nurgling.widgets.TextInputWindow;

import java.util.ArrayList;
import java.util.List;

/**
 * Quality Hunter's settings: a mark-merge radius, a tracking-profile picker (which groups are
 * active for detection right now -- see QualityHunterProfiles), and one drag-and-drop list per
 * group (see QualityHunterContainer). An item dropped into two different groups gets two
 * independent entries, each with its own threshold and its own tracking toggle.
 */
public class QualityHunterSettings extends Panel {

    private static final int DEFAULT_RADIUS = 100;
    private static final int CONT_W = UI.scale(540);
    private static final int GROUP_W = UI.scale(205);
    private static final int GROUP_H = UI.scale(105);

    private final Scrollport scroll;
    private final Scrollport.Scrollcont cont;
    private TextEntry radiusEntry;
    private Dropbox<String> profileDropbox;
    private Widget groupsAnchor;
    private final List<GroupBlock> groupBlocks = new ArrayList<>();

    /** Base for this panel's "list of names" dropdowns, mirroring ForagerSettingsPanel's own. */
    private abstract class NamesDropbox extends Dropbox<String> {
        NamesDropbox(int w, int h, int itemh) {
            super(w, h, itemh);
        }

        protected abstract List<String> names();

        @Override
        protected String listitem(int i) {
            return names().get(i);
        }

        @Override
        protected int listitems() {
            return names().size();
        }

        @Override
        protected void drawitem(GOut g, String item, int i) {
            g.text(item, Coord.z);
        }
    }

    public QualityHunterSettings() {
        super(L10n.get("quality_hunter.title"));

        // Scrollable viewport: an open-ended number of groups easily runs past 580x580.
        scroll = add(new Scrollport(UI.scale(new Coord(560, 530))), UI.scale(10, 40));
        cont = scroll.cont;

        Widget prev = cont.add(new Label(L10n.get("quality_hunter.radius")), Coord.z);
        radiusEntry = cont.add(new TextEntry(UI.scale(60), String.valueOf(DEFAULT_RADIUS)), prev.pos("ur").add(UI.scale(10), -UI.scale(3)));

        prev = cont.add(new Label(L10n.get("quality_hunter.profile")), prev.pos("bl").add(0, UI.scale(15)));
        Widget profileRow = cont.add(new Widget(new Coord(UI.scale(280), UI.scale(20))), prev.pos("bl").add(0, UI.scale(5)));
        profileDropbox = profileRow.add(new NamesDropbox(UI.scale(200), 8, UI.scale(16)) {
            @Override
            protected List<String> names() {
                return QualityHunterProfiles.getProfileNames();
            }

            @Override
            public void change(String item) {
                super.change(item);
                if (item != null) QualityHunterProfiles.setCurrentProfile(item);
            }
        }, new Coord(0, 0));
        profileDropbox.change(QualityHunterProfiles.getCurrentProfile());

        profileRow.add(new IButton(
                Resource.loadsimg("nurgling/hud/buttons/add/u"),
                Resource.loadsimg("nurgling/hud/buttons/add/d"),
                Resource.loadsimg("nurgling/hud/buttons/add/h")) {
            @Override
            public void click() {
                super.click();
                addProfile();
            }
        }, new Coord(UI.scale(210), 0)).settip(L10n.get("quality_hunter.new_profile_tip"));

        profileRow.add(new IButton(
                Resource.loadsimg("nurgling/hud/buttons/remove/u"),
                Resource.loadsimg("nurgling/hud/buttons/remove/d"),
                Resource.loadsimg("nurgling/hud/buttons/remove/h")) {
            @Override
            public void click() {
                super.click();
                deleteProfile();
            }
        }, new Coord(UI.scale(240), 0)).settip(L10n.get("quality_hunter.delete_profile_tip"));

        prev = cont.add(new Label(L10n.get("quality_hunter.groups")), profileRow.pos("bl").add(0, UI.scale(15)));
        cont.add(new IButton(
                Resource.loadsimg("nurgling/hud/buttons/add/u"),
                Resource.loadsimg("nurgling/hud/buttons/add/d"),
                Resource.loadsimg("nurgling/hud/buttons/add/h")) {
            @Override
            public void click() {
                super.click();
                addGroup();
            }
        }, prev.pos("ur").add(UI.scale(8), -UI.scale(2))).settip(L10n.get("quality_hunter.new_group_tip"));

        groupsAnchor = cont.add(new Widget(Coord.z), prev.pos("bl").add(0, UI.scale(10)));
        reloadGroups();
    }

    private void addProfile() {
        TextInputWindow win = new TextInputWindow(
                L10n.get("quality_hunter.new_profile_title"), L10n.get("quality_hunter.new_profile_prompt"), name -> {
            if (name == null) return;
            QualityHunterProfiles.addProfile(name);
            QualityHunterProfiles.setCurrentProfile(name);
            profileDropbox.change(name);
        });
        NUtils.getGameUI().add(win, UI.scale(250, 250));
        win.show();
    }

    private void deleteProfile() {
        String current = profileDropbox.sel;
        if (current == null || current.equals(QualityHunterContainer.DEFAULT_PROFILE)) return;
        QualityHunterProfiles.deleteProfile(current);
        profileDropbox.change(QualityHunterProfiles.getCurrentProfile());
    }

    private void addGroup() {
        TextInputWindow win = new TextInputWindow(
                L10n.get("quality_hunter.new_group_title"), L10n.get("quality_hunter.new_group_prompt"), name -> {
            if (name == null) return;
            QualityHunterContainer.addGroup(name);
            rebuildGroupsPreservingEdits();
        });
        NUtils.getGameUI().add(win, UI.scale(250, 250));
        win.show();
    }

    private void renameGroup(String oldName) {
        TextInputWindow win = new TextInputWindow(
                L10n.get("quality_hunter.rename_group_title"), L10n.get("quality_hunter.rename_group_prompt"), newName -> {
            if (newName == null) return;
            QualityHunterContainer.renameGroup(oldName, newName);
            rebuildGroupsPreservingEdits();
        });
        NUtils.getGameUI().add(win, UI.scale(250, 250));
        win.show();
    }

    private void deleteGroup(String name) {
        QualityHunterContainer.deleteGroup(name);
        rebuildGroupsPreservingEdits();
    }

    /** One group's header (active-in-profile checkbox, rename/delete) plus its drag-drop list. */
    private class GroupBlock {
        final String name;
        final Widget header;
        final QualityHunterContainer container;
        final int height;

        GroupBlock(String name, int y) {
            this.name = name;
            header = cont.add(new Widget(new Coord(CONT_W, UI.scale(20))), new Coord(0, y));
            CheckBox activeBox = header.add(new CheckBox(name), Coord.z);
            activeBox.state(() -> QualityHunterProfiles.isGroupActive(name));
            activeBox.set(val -> QualityHunterProfiles.setGroupActive(name, val));
            activeBox.settip(L10n.get("quality_hunter.group_active_tip"));

            header.add(new Button(UI.scale(80), L10n.get("quality_hunter.rename_group")) {
                @Override
                public void click() {
                    renameGroup(name);
                }
            }, new Coord(UI.scale(260), 0));

            header.add(new IButton(
                    Resource.loadsimg("nurgling/hud/buttons/remove/u"),
                    Resource.loadsimg("nurgling/hud/buttons/remove/d"),
                    Resource.loadsimg("nurgling/hud/buttons/remove/h")) {
                @Override
                public void click() {
                    super.click();
                    deleteGroup(name);
                }
            }, new Coord(UI.scale(345), 2)).settip(L10n.get("quality_hunter.delete_group_tip"));

            container = cont.add(new QualityHunterContainer(name), new Coord(0, y + UI.scale(24)));
            container.resize(new Coord(GROUP_W, GROUP_H));
            container.load();

            height = UI.scale(24) + GROUP_H + UI.scale(10);
        }

        void destroy() {
            header.destroy();
            container.destroy();
        }
    }

    /** Destroys and rebuilds every group block straight from storage, discarding any unsaved
     *  item-level edits -- the usual "reload = cancel my pending edits" contract, used when the
     *  panel is (re)opened. */
    private void reloadGroups() {
        for (GroupBlock b : groupBlocks) b.destroy();
        groupBlocks.clear();

        int y = groupsAnchor.c.y;
        for (String name : QualityHunterContainer.getGroupNames()) {
            GroupBlock b = new GroupBlock(name, y);
            groupBlocks.add(b);
            y += b.height;
        }
        cont.update();
    }

    /** Same rebuild, but for a group add/rename/delete: that's a structural change to the group
     *  list itself, not a "give up on my edits" request, so every OTHER still-existing group's
     *  unsaved drag-drop edits are committed first instead of silently discarding them. (Not the
     *  group just deleted/renamed away, which storage no longer has room for.) */
    private void rebuildGroupsPreservingEdits() {
        List<String> current = QualityHunterContainer.getGroupNames();
        for (GroupBlock b : groupBlocks) {
            if (current.contains(b.name)) b.container.save();
        }
        reloadGroups();
    }

    @Override
    public void load() {
        radiusEntry.settext(String.valueOf(getConfigInt(NConfig.Key.qualityHunterMarkRadius, DEFAULT_RADIUS)));
        profileDropbox.change(QualityHunterProfiles.getCurrentProfile());
        reloadGroups();
    }

    @Override
    public void save() {
        NConfig.set(NConfig.Key.qualityHunterMarkRadius, parseIntSafe(radiusEntry.text(), getConfigInt(NConfig.Key.qualityHunterMarkRadius, DEFAULT_RADIUS)));
        for (GroupBlock b : groupBlocks) b.container.save();
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
