package nurgling.widgets;

import haven.Coord;
import haven.TexI;
import haven.UI;
import haven.res.lib.itemtex.ItemTex;
import nurgling.NConfig;
import nurgling.NGItem;
import nurgling.NStyle;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Drag-and-drop editor for one named group of Quality Hunter's watch list. Each group is its own
 * instance of this container (see QualityHunterSettings), so an item dropped into two different
 * groups gets two independent entries -- each with its own threshold and its own active flag.
 *
 * Groups are stored as {@link NConfig.Key#qualityHunterGroups}: a JSONObject mapping group name ->
 * JSONArray of item entries ({name, th, active, ...icon fields}). Which groups are active for
 * detection right now is a separate, smaller structure on top -- see {@link QualityHunterProfiles}.
 */
public class QualityHunterContainer extends BaseIngredientContainer {

    public static final String DEFAULT_PROFILE = "Default";

    private final String group;
    JSONArray jitems = new JSONArray();

    public QualityHunterContainer(String group) {
        super("qualityhunter");
        this.group = group;
    }

    // ================= Groups (cross-instance, static) =================

    private static volatile JSONObject cachedGroups = null;

    static JSONObject copyOf(JSONObject src) {
        return new JSONObject(src.toString());
    }

    private static JSONArray copyOf(JSONArray src) {
        JSONArray dst = new JSONArray();
        for (int i = 0; i < src.length(); i++) {
            Object o = src.get(i);
            dst.put(o instanceof JSONObject ? new JSONObject(o.toString()) : o);
        }
        return dst;
    }

    /** Deep copy of the stored groups map, migrating the old single-list format the first time
     *  it's read if nothing has been migrated yet (an empty qualityHunterGroups but a non-empty
     *  legacy qualityHunterConf). Migrated once, then qualityHunterConf is left alone (it's no
     *  longer written to), so this only ever runs on an upgrade from the pre-groups version. */
    private static JSONObject readGroupsStored() {
        Object stored = NConfig.getGlobal(NConfig.Key.qualityHunterGroups);
        JSONObject groups = (stored instanceof JSONObject) ? copyOf((JSONObject) stored) : new JSONObject();
        if (groups.length() == 0) {
            JSONObject migrated = migrateLegacyConf();
            if (migrated != null) return migrated;
        }
        return groups;
    }

    /** One-time import of the pre-overhaul flat list: each item's old "group" string (or "" for
     *  none) becomes the name of a group it's placed into. Returns null if there was nothing to
     *  migrate. */
    private static JSONObject migrateLegacyConf() {
        Object legacy = NConfig.getGlobal(NConfig.Key.qualityHunterConf);
        JSONArray legacyItems = (legacy instanceof JSONArray) ? (JSONArray) legacy : null;
        if (legacyItems == null || legacyItems.length() == 0) return null;

        JSONObject groups = new JSONObject();
        for (int i = 0; i < legacyItems.length(); i++) {
            JSONObject item = new JSONObject(legacyItems.getJSONObject(i).toString());
            String oldGroup = item.optString("group", "");
            item.remove("group");
            item.put("active", true);
            String groupName = oldGroup.isEmpty() ? "Ungrouped" : oldGroup;
            JSONArray bucket = groups.has(groupName) ? groups.getJSONArray(groupName) : new JSONArray();
            bucket.put(item);
            groups.put(groupName, bucket);
        }
        NConfig.set(NConfig.Key.qualityHunterGroups, groups);
        return groups;
    }

    private static JSONObject groups() {
        JSONObject cached = cachedGroups;
        if (cached != null) return cached;
        JSONObject fresh = readGroupsStored();
        if (fresh.length() > 0) cachedGroups = fresh;
        return fresh;
    }

    public static void invalidateCache() {
        cachedGroups = null;
    }

    private static void store(JSONObject groups) {
        NConfig.set(NConfig.Key.qualityHunterGroups, groups);
        invalidateCache();
    }

    public static List<String> getGroupNames() {
        return new ArrayList<>(new TreeSet<>(groups().keySet()));
    }

    static JSONArray getGroupItemsRaw(String group) {
        JSONObject groups = groups();
        return groups.has(group) ? groups.getJSONArray(group) : new JSONArray();
    }

    public static void addGroup(String name) {
        JSONObject groups = copyOf(groups());
        if (!groups.has(name)) {
            groups.put(name, new JSONArray());
            store(groups);
        }
    }

    public static void renameGroup(String oldName, String newName) {
        if (oldName.equals(newName)) return;
        JSONObject groups = copyOf(groups());
        if (!groups.has(oldName) || groups.has(newName)) return;
        groups.put(newName, groups.getJSONArray(oldName));
        groups.remove(oldName);
        store(groups);
        QualityHunterProfiles.renameGroupEverywhere(oldName, newName);
    }

    public static void deleteGroup(String name) {
        JSONObject groups = copyOf(groups());
        if (!groups.has(name)) return;
        groups.remove(name);
        store(groups);
        QualityHunterProfiles.removeGroupEverywhere(name);
    }

    // ================= Aggregate view (detection engine, classification) =================

    /** One tracked entry: an item placed in a group, with that group's threshold and this
     *  particular placement's own on/off flag. */
    public static final class Entry {
        public final String group;
        public final String name;
        public final int threshold;
        public final boolean active;

        Entry(String group, String name, int threshold, boolean active) {
            this.group = group;
            this.name = name;
            this.threshold = threshold;
            this.active = active;
        }
    }

    /** Every entry in every group, regardless of profile or active-flag -- callers filter as
     *  needed. Used by the detection engine (which also applies profile + active-flag) and by
     *  classification (which a mark's resource type should read as Quality Hunter regardless of
     *  whether it's currently being tracked). */
    public static List<Entry> getAllEntries() {
        List<Entry> all = new ArrayList<>();
        JSONObject groups = groups();
        for (String group : groups.keySet()) {
            JSONArray items = groups.getJSONArray(group);
            for (int i = 0; i < items.length(); i++) {
                JSONObject jo = items.getJSONObject(i);
                String name = jo.getString("name");
                int th = jo.has("th") ? jo.getInt("th") : 0;
                boolean active = !jo.has("active") || jo.getBoolean("active");
                all.add(new Entry(group, name, th, active));
            }
        }
        return all;
    }

    /** Whether any group anywhere lists this resource name, regardless of active/profile state --
     *  the question a mark's own classification needs answered, independent of live tracking. */
    public static boolean isKnownItemName(String resourceType) {
        for (Entry e : getAllEntries()) {
            if (e.name.equals(resourceType)) return true;
        }
        return false;
    }

    // ================= Instance: one group's drag-and-drop list =================

    @Override
    public void addItem(String name, JSONObject res) {
        if (res != null) {
            res.put("name", name);
            res.put("active", true);
            addIcon(res);
            jitems.put(res);
        }
    }

    @Override
    public void delete(String name) {
        super.delete(name);
        for (int i = 0; i < jitems.length(); i++) {
            if (((JSONObject) jitems.get(i)).get("name").equals(name)) {
                jitems.remove(i);
                break;
            }
        }
        rebuildIcons();
    }

    @Override
    public void deleteAll() {
        super.deleteAll();
        jitems.clear();
    }

    @Override
    public boolean drop(Drop ev) {
        String name = ((NGItem) ev.src.item).name();
        JSONObject res = ItemTex.save(((NGItem) ev.src.item).spr);
        addItem(name, res);
        return super.drop(ev);
    }

    public void load() {
        items.clear();
        for (IconItem it : icons) {
            it.destroy();
        }
        icons.clear();

        jitems = copyOf(getGroupItemsRaw(group));

        for (int i = 0; i < jitems.length(); i++) {
            addIcon(jitems.getJSONObject(i));
        }
    }

    private void rebuildIcons() {
        items.clear();
        for (IconItem it : icons) {
            it.destroy();
        }
        icons.clear();
        for (int i = 0; i < jitems.length(); i++) {
            addIcon(jitems.getJSONObject(i));
        }
    }

    public void addIcon(JSONObject res) {
        if (res != null && res.get("name") != null) {
            Ingredient ing;
            items.add(ing = new Ingredient((String) res.get("name"), ItemTex.create(res)));
            IconItem it = add(new IconItem(ing.name, ing.image, this), UI.scale(new Coord(35 * ((items.size() - 1) % 5), 51 * ((items.size() - 1) / 5))).add(new Coord(5, 5)));
            it.basec = new Coord(it.c);
            maxy = UI.scale(51) * ((items.size() - 1) / 5 - 5);
            cury = Math.min(cury, Math.max(maxy, 0));
            if (res.has("th")) {
                it.hasBadge = true;
                it.val = (Integer) res.get("th");
                it.q = new TexI(NStyle.iiqual.render(String.valueOf(it.val)).img);
            }
            it.qhActive = !res.has("active") || res.getBoolean("active");
            icons.add(it);
        }
    }

    public void setThreshold(String name, int val) {
        for (int i = 0; i < jitems.length(); i++) {
            JSONObject jo = (JSONObject) jitems.get(i);
            if (jo.get("name").equals(name)) {
                if (val > 0) {
                    jo.put("th", val);
                } else {
                    jo.remove("th");
                }
                return;
            }
        }
    }

    /** Toggles one entry's own on/off flag -- independent of the group's active state in the
     *  current profile, so an item can be temporarily skipped without removing it from the group. */
    public void setActive(String name, boolean active) {
        for (int i = 0; i < jitems.length(); i++) {
            JSONObject jo = (JSONObject) jitems.get(i);
            if (jo.get("name").equals(name)) {
                jo.put("active", active);
                for (IconItem it : icons) {
                    if (it.name.equals(name)) it.qhActive = active;
                }
                return;
            }
        }
    }

    /** Snapshot of this group's list, safe to hand to NConfig -- called by QualityHunterSettings.save(). */
    public JSONArray getJsonCopy() {
        return copyOf(jitems);
    }

    /** Commits this group's current (in-memory) item list to storage. Item-level edits (drag-drop,
     *  threshold, the per-entry tracking toggle) are deferred until save, same as DropContainer /
     *  IngredientContainer -- only group/profile structure (add/rename/delete a group or profile)
     *  commits immediately. */
    public void save() {
        JSONObject groups = copyOf(groups());
        groups.put(group, getJsonCopy());
        store(groups);
    }

    // ================= Minimap/search display visibility (independent of tracking) =================

    /** Whether marks belonging to a group (as recorded on the mark itself -- see
     *  LabeledMinimapMark.groups) should be drawn. Unset (never explicitly hidden) means visible. */
    public static boolean isGroupVisible(String group) {
        Object stored = NConfig.getGlobal(NConfig.Key.qualityHunterGroupVisibility);
        if (!(stored instanceof JSONObject)) return true;
        JSONObject obj = (JSONObject) stored;
        String key = (group == null) ? "" : group;
        return !obj.has(key) || obj.getBoolean(key);
    }

    public static void setGroupVisible(String group, boolean visible) {
        Object stored = NConfig.getGlobal(NConfig.Key.qualityHunterGroupVisibility);
        JSONObject obj = (stored instanceof JSONObject) ? new JSONObject(stored.toString()) : new JSONObject();
        obj.put((group == null) ? "" : group, visible);
        NConfig.set(NConfig.Key.qualityHunterGroupVisibility, obj);
    }

    /** Whether marks of a specific resource name should be drawn -- independent of, and combined
     *  (AND) with, group visibility. */
    public static boolean isNameVisible(String name) {
        Object stored = NConfig.getGlobal(NConfig.Key.qualityHunterNameVisibility);
        if (!(stored instanceof JSONObject) || name == null) return true;
        JSONObject obj = (JSONObject) stored;
        return !obj.has(name) || obj.getBoolean(name);
    }

    public static void setNameVisible(String name, boolean visible) {
        if (name == null) return;
        Object stored = NConfig.getGlobal(NConfig.Key.qualityHunterNameVisibility);
        JSONObject obj = (stored instanceof JSONObject) ? new JSONObject(stored.toString()) : new JSONObject();
        obj.put(name, visible);
        NConfig.set(NConfig.Key.qualityHunterNameVisibility, obj);
    }
}
