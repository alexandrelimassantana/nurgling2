package nurgling.widgets;

import nurgling.NConfig;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Named tracking profiles: which groups are active for detection right now. Exactly one profile
 * is active at a time. The built-in {@link QualityHunterContainer#DEFAULT_PROFILE} is not stored
 * -- it always means every group, so it can never go stale as groups are added -- and is the only
 * profile that can't be renamed or deleted.
 */
public class QualityHunterProfiles {
    private QualityHunterProfiles() {}

    private static JSONObject profiles() {
        Object stored = NConfig.getGlobal(NConfig.Key.qualityHunterProfiles);
        return (stored instanceof JSONObject) ? QualityHunterContainer.copyOf((JSONObject) stored) : new JSONObject();
    }

    private static void store(JSONObject profiles) {
        NConfig.set(NConfig.Key.qualityHunterProfiles, profiles);
    }

    public static List<String> getProfileNames() {
        List<String> names = new ArrayList<>();
        names.add(QualityHunterContainer.DEFAULT_PROFILE);
        names.addAll(new TreeSet<>(profiles().keySet()));
        return names;
    }

    /** Falls back to Default if the stored current profile was since deleted. */
    public static String getCurrentProfile() {
        Object stored = NConfig.getGlobal(NConfig.Key.qualityHunterCurrentProfile);
        String name = (stored instanceof String) ? (String) stored : QualityHunterContainer.DEFAULT_PROFILE;
        if (!name.equals(QualityHunterContainer.DEFAULT_PROFILE) && !profiles().has(name))
            return QualityHunterContainer.DEFAULT_PROFILE;
        return name;
    }

    public static void setCurrentProfile(String name) {
        NConfig.set(NConfig.Key.qualityHunterCurrentProfile, name);
    }

    /** New profiles start with nothing active -- an explicit, curated subset, not a snapshot of
     *  whatever happened to be active before. */
    public static void addProfile(String name) {
        if (name.equals(QualityHunterContainer.DEFAULT_PROFILE)) return;
        JSONObject profiles = profiles();
        if (!profiles.has(name)) {
            profiles.put(name, new JSONArray());
            store(profiles);
        }
    }

    public static void deleteProfile(String name) {
        if (name.equals(QualityHunterContainer.DEFAULT_PROFILE)) return;
        JSONObject profiles = profiles();
        if (!profiles.has(name)) return;
        profiles.remove(name);
        store(profiles);
        if (getCurrentProfile().equals(name)) setCurrentProfile(QualityHunterContainer.DEFAULT_PROFILE);
    }

    public static boolean isGroupActive(String group) {
        String current = getCurrentProfile();
        if (current.equals(QualityHunterContainer.DEFAULT_PROFILE)) return true;
        return activeSet(profiles(), current).contains(group);
    }

    /** No-op while Default is selected -- it's fixed to "every group" by definition. */
    public static void setGroupActive(String group, boolean active) {
        String current = getCurrentProfile();
        if (current.equals(QualityHunterContainer.DEFAULT_PROFILE)) return;
        JSONObject profiles = profiles();
        Set<String> set = activeSet(profiles, current);
        if (active) set.add(group); else set.remove(group);
        profiles.put(current, new JSONArray(set));
        store(profiles);
    }

    private static Set<String> activeSet(JSONObject profiles, String profile) {
        Set<String> set = new LinkedHashSet<>();
        if (profiles.has(profile)) {
            JSONArray arr = profiles.getJSONArray(profile);
            for (int i = 0; i < arr.length(); i++) set.add(arr.getString(i));
        }
        return set;
    }

    /** Keeps every profile's active-group references in sync with a group rename. */
    static void renameGroupEverywhere(String oldName, String newName) {
        JSONObject profiles = profiles();
        boolean changed = false;
        for (String name : profiles.keySet()) {
            Set<String> set = activeSet(profiles, name);
            if (set.remove(oldName)) {
                set.add(newName);
                profiles.put(name, new JSONArray(set));
                changed = true;
            }
        }
        if (changed) store(profiles);
    }

    /** Drops a deleted group from every profile's active set. */
    static void removeGroupEverywhere(String group) {
        JSONObject profiles = profiles();
        boolean changed = false;
        for (String name : profiles.keySet()) {
            Set<String> set = activeSet(profiles, name);
            if (set.remove(group)) {
                profiles.put(name, new JSONArray(set));
                changed = true;
            }
        }
        if (changed) store(profiles);
    }
}
