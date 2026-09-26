package nurgling.widgets;

import haven.Coord;
import haven.TexI;
import haven.UI;
import haven.res.lib.itemtex.ItemTex;
import nurgling.NConfig;
import nurgling.NGItem;
import nurgling.NStyle;
import nurgling.NUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Drag-and-drop editor for the Forage Tracker's watch list: which items to
 * mark on the map, and from what quality. Mirrors {@link DropContainer}
 * (same global, non-area-scoped storage), but the stored "th" here is a
 * quality floor to mark rather than a quantity to drop.
 */
public class ForageTrackerContainer extends BaseIngredientContainer {

    JSONArray jitems = new JSONArray();

    public ForageTrackerContainer() {
        super("forage");
    }

    private static volatile HashMap<String, Integer> cachedProps = null;

    /**
     * Deep copy so the panel and the config never share mutable JSON, the same
     * precaution {@link DropContainer} takes for the same reason.
     */
    private static JSONArray copyOf(JSONArray src) {
        JSONArray dst = new JSONArray();
        for (int i = 0; i < src.length(); i++) {
            Object o = src.get(i);
            dst.put(o instanceof JSONObject ? new JSONObject(o.toString()) : o);
        }
        return dst;
    }

    private static JSONArray readStored() {
        Object stored = NConfig.getGlobal(NConfig.Key.forageTrackerConf);
        if (stored instanceof JSONArray) {
            return copyOf((JSONArray) stored);
        } else if (stored != null) {
            return new JSONArray((ArrayList<HashMap<String, Object>>) stored);
        }
        return new JSONArray();
    }

    /** Snapshot of the panel's list, safe to hand to NConfig. */
    public JSONArray getJsonCopy() {
        return copyOf(jitems);
    }

    /**
     * Item name -> minimum quality to mark. An item with no threshold set
     * (or a cleared one) is marked at any quality.
     */
    public static HashMap<String, Integer> getForageProps() {
        HashMap<String, Integer> cached = cachedProps;
        if (cached != null) return cached;

        // Read from the GLOBAL config (the disk-backed instance), same reasoning
        // as DropContainer.getDropProps(): a per-session config instance can
        // transiently serve an empty list even while the on-disk value is intact.
        JSONArray data = readStored();

        HashMap<String, Integer> props = new HashMap<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject jsonObject = ((JSONObject) data.get(i));
            String name = jsonObject.getString("name");
            int th = jsonObject.has("th") ? jsonObject.getInt("th") : 0;
            props.put(name, th);
        }
        // Never cache an empty result: a one-off empty read (e.g. before the
        // config has loaded) must not poison the cache for the whole session.
        if (!props.isEmpty()) {
            cachedProps = props;
        }
        return props;
    }

    public static void invalidateCache() {
        cachedProps = null;
    }

    @Override
    public void addItem(String name, JSONObject res) {
        if (res != null) {
            res.put("name", name);
            addIcon(res);
            jitems.put(res);
            invalidateCache();
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
        items.clear();
        for (IconItem it : icons) {
            it.destroy();
        }
        icons.clear();
        for (int i = 0; i < jitems.length(); i++) {
            addIcon(((JSONObject) jitems.get(i)));
        }
        invalidateCache();
    }

    @Override
    public void deleteAll() {
        super.deleteAll();
        jitems.clear();
        invalidateCache();
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

        // Read from the global config (the disk-backed instance) so the panel
        // always reflects the real saved list, never a transient empty value
        // from a per-session config instance. Do NOT clear/mutate the old
        // jitems here for the same reason DropContainer.load() doesn't:
        // readStored() returns a private deep copy, so just replace the field.
        jitems = readStored();

        for (int i = 0; i < jitems.length(); i++) {
            addIcon(((JSONObject) jitems.get(i)));
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
                invalidateCache();
                return;
            }
        }
    }
}
