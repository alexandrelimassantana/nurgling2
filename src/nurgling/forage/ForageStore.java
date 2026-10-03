package nurgling.forage;

import nurgling.NConfig;
import nurgling.tools.NFileUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Every forage find of one world, shared by all sessions logged into that world.
 *
 * <p>Built like {@link nurgling.timers.TimerStore}: {@link nurgling.sessions.SessionManager#forageStore}
 * hands out one instance per genus, the file always holds everything so the map works offline, and when a
 * database is connected {@link nurgling.db.service.ForageSyncService} pushes the pending edits and applies
 * the rows other villagers changed. A find the database has never confirmed has version 0 and waits in
 * {@link #pendingUpsert}, so finds made before the database was switched on are uploaded on the first sync.
 *
 * <p>Reads are lock-free: the render path takes {@link #finds()}, an immutable snapshot swapped on every
 * change. File writes are coalesced onto a background thread, because recording happens on the UI thread
 * and the file grows with every pick.
 */
public class ForageStore {
    /** The fixed range, in tiles, used while {@link #keepBest()} is off. */
    public static final int LEGACY_NEAR_TILES = 3;
    /** Default for {@link #nearTiles()}, the range used while {@link #keepBest()} is on. */
    public static final int DEFAULT_NEAR_TILES = 100;
    /** Upper bound for the range: generous, and keeps the tile-to-world multiplication far from overflow. */
    public static final int MAX_NEAR_TILES = 1000;

    /** A new find replaces an older find of the same item this close to it, in tiles; set in Map Tools. */
    public static int nearTiles() {
        Object val = NConfig.get(NConfig.Key.forageNearTiles);
        int tiles = (val instanceof Number) ? ((Number) val).intValue() : DEFAULT_NEAR_TILES;
        return Math.max(0, Math.min(MAX_NEAR_TILES, tiles));
    }

    public static void nearTiles(int tiles) {
        NConfig.set(NConfig.Key.forageNearTiles, Math.max(0, Math.min(MAX_NEAR_TILES, tiles)));
    }

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Forage-Finds-Writer");
        t.setDaemon(true);
        return t;
    });

    private final String genus;
    private final String path;

    private final Map<String, ForageFind> finds = new LinkedHashMap<>();
    /** Finds whose current content the database has not confirmed, with the edit generation. */
    private final Map<String, Long> pendingUpsert = new LinkedHashMap<>();
    /** Ids to tombstone in the database. */
    private final Map<String, Long> pendingDelete = new LinkedHashMap<>();
    private long generation = 0;

    private volatile List<ForageFind> snapshot = Collections.emptyList();
    private volatile long revision = 0;

    private final Object saveLock = new Object();
    private final AtomicBoolean saveQueued = new AtomicBoolean(false);

    public ForageStore(String genus, String path) {
        this.genus = genus;
        this.path = path;
        load();
    }

    public String genus() {return genus;}

    /** The database profile this world's rows are filed under. */
    public String profile() {return genus.isEmpty() ? "global" : genus;}

    /** Bumped on every change; the map compares it to decide whether to regroup. */
    public long revision() {return revision;}

    /** Every find, in no particular order. Safe to hold: the list and the finds never change. */
    public List<ForageFind> finds() {return snapshot;}

    // -------------------- Edits from this client --------------------

    /**
     * Whether "keep the best find in range" is on (Map Tools). Off, a find replaces any nearby find of the
     * same item in its grid within {@link #LEGACY_NEAR_TILES}; on, {@link #nearTiles()} sets the range and
     * only a better find replaces a nearby one.
     */
    public static boolean keepBest() {
        Object val = NConfig.get(NConfig.Key.forageKeepBest);
        return (val instanceof Boolean) && (Boolean) val;
    }

    public static void keepBest(boolean val) {
        NConfig.set(NConfig.Key.forageKeepBest, val);
    }

    /**
     * Record a find. With {@link #keepBest()} off a new find replaces any nearby find of the same item.
     * With it on, nearby finds of the same item (within {@link #nearTiles()}) are compared with it: if any
     * of them is at least as good the new find is dropped and nothing moves; otherwise it is better than
     * all of them and replaces the ones its own picker made. Other pickers' finds are never deleted by
     * it, since a deletion reaches every player on the world through the database. Returns whether the
     * find was recorded.
     */
    public boolean add(ForageFind f) {
        return add(f, null);
    }

    /**
     * As {@link #add(ForageFind)}. With keep-best on, finds in different grids cannot be compared by their
     * offsets, so a find just across a grid border would never be recognised as a neighbour; {@code worldPos}
     * gives a find's position in this session's world (null while its grid is not loaded) and is used
     * to compare such finds by distance instead.
     */
    public boolean add(ForageFind f, java.util.function.Function<ForageFind, haven.Coord2d> worldPos) {
        boolean best = keepBest();
        int near = best ? nearTiles() : LEGACY_NEAR_TILES;
        haven.Coord2d fpos = (best && worldPos != null) ? worldPos.apply(f) : null;
        synchronized(this) {
            List<ForageFind> replaced = new ArrayList<>();
            for(ForageFind old : finds.values()) {
                if(old.id.equals(f.id) || !old.itemName.equals(f.itemName))
                    continue;
                if(!old.isNear(f, near) && !isNearAcrossGrids(old, f, fpos, worldPos, near))
                    continue;
                if(best && old.quality >= f.quality)
                    return false;
                if(!best || old.foundBy.equals(f.foundBy))
                    replaced.add(old);
            }
            for(ForageFind old : replaced) {
                finds.remove(old.id);
                forget(old);
            }
            finds.put(f.id, f);
            pendingUpsert.put(f.id, ++generation);
            pendingDelete.remove(f.id);
        }
        changed();
        return true;
    }

    private static boolean isNearAcrossGrids(ForageFind old, ForageFind f, haven.Coord2d fpos,
                                             java.util.function.Function<ForageFind, haven.Coord2d> worldPos, int tiles) {
        if(fpos == null || old.gridId == f.gridId)
            return false;
        haven.Coord2d opos = worldPos.apply(old);
        if(opos == null)
            return false;
        return Math.abs(opos.x - fpos.x) <= tiles * haven.MCache.tilesz.x
            && Math.abs(opos.y - fpos.y) <= tiles * haven.MCache.tilesz.y;
    }

    /** Delete a find, for everyone when the database is on. */
    public void remove(String id) {
        removeAll(Collections.singleton(id));
    }

    public void removeAll(Collection<String> ids) {
        boolean any = false;
        synchronized(this) {
            for(String id : ids) {
                ForageFind f = finds.remove(id);
                if(f != null) {
                    forget(f);
                    any = true;
                }
            }
        }
        if(any)
            changed();
    }

    /** Called under the lock for a find just taken out of {@link #finds}. */
    private void forget(ForageFind f) {
        pendingUpsert.remove(f.id);
        if(f.version > 0)
            pendingDelete.put(f.id, ++generation);
    }

    // -------------------- Database sync --------------------

    /** A pending database write with the edit generation it belongs to. */
    public static final class Pending<T> {
        public final T item;
        public final long gen;

        Pending(T item, long gen) {
            this.item = item;
            this.gen = gen;
        }
    }

    public synchronized List<Pending<ForageFind>> pendingUpserts() {
        List<Pending<ForageFind>> out = new ArrayList<>();
        for(Map.Entry<String, Long> e : pendingUpsert.entrySet()) {
            ForageFind f = finds.get(e.getKey());
            if(f != null)
                out.add(new Pending<>(f, e.getValue()));
        }
        return out;
    }

    public synchronized List<Pending<String>> pendingDeletes() {
        List<Pending<String>> out = new ArrayList<>();
        for(Map.Entry<String, Long> e : pendingDelete.entrySet())
            out.add(new Pending<>(e.getKey(), e.getValue()));
        return out;
    }

    /**
     * The database took these uploads; {@code versions} maps id to row version (-1 for a tombstone).
     * A newer edit made meanwhile stays pending. Only versions change, which nothing draws, so the
     * snapshot is swapped without bumping {@link #revision} and the map does not regroup.
     */
    public void confirmUpserts(List<Pending<ForageFind>> batch, Map<String, Integer> versions) {
        synchronized(this) {
            for(Pending<ForageFind> p : batch) {
                String id = p.item.id;
                Integer v = versions.get(id);
                if(v == null)
                    continue;   // not in the table after all; stays pending for the next tick
                Long cur = pendingUpsert.get(id);
                if(cur != null && cur == p.gen)
                    pendingUpsert.remove(id);
                ForageFind f = finds.get(id);
                if(f != null)
                    finds.put(id, f.withVersion(Math.max(v, 1)));
                else if(!pendingUpsert.containsKey(id))
                    pendingDelete.put(id, ++generation);   // deleted while the upload was on its way
            }
            snapshot = Collections.unmodifiableList(new ArrayList<>(finds.values()));
        }
        scheduleSave();
    }

    public void confirmDelete(String id, long gen) {
        synchronized(this) {
            Long cur = pendingDelete.get(id);
            if(cur != null && cur == gen)
                pendingDelete.remove(id);
        }
        scheduleSave();
    }

    /**
     * Apply what the database says.
     *
     * @param live       rows that exist
     * @param tombstoned ids somebody deleted
     * @param full       whether {@code live} is every row of this world (a bulk load); a find missing from
     *                   a full load was deleted and purged if it had been synced, or never uploaded if not
     */
    public void applyRemote(List<ForageFind> live, Collection<String> tombstoned, boolean full) {
        boolean any = false;
        synchronized(this) {
            Set<String> seen = new HashSet<>();
            for(ForageFind row : live) {
                seen.add(row.id);
                if(pendingUpsert.containsKey(row.id) || pendingDelete.containsKey(row.id))
                    continue;   // our own edit is on its way; it wins
                ForageFind old = finds.get(row.id);
                if(old != null && old.version == row.version)
                    continue;
                finds.put(row.id, row);
                any = true;
            }
            for(String id : tombstoned) {
                if(pendingUpsert.containsKey(id))
                    continue;
                pendingDelete.remove(id);
                if(finds.remove(id) != null)
                    any = true;
            }
            if(full) {
                for(Iterator<ForageFind> it = finds.values().iterator(); it.hasNext(); ) {
                    ForageFind f = it.next();
                    if(seen.contains(f.id) || tombstoned.contains(f.id) || pendingUpsert.containsKey(f.id))
                        continue;
                    if(f.version > 0)
                        it.remove();
                    else
                        pendingUpsert.put(f.id, ++generation);
                    any = true;
                }
            }
        }
        if(any)
            changed();
    }

    // -------------------- Persistence --------------------

    private void changed() {
        synchronized(this) {
            snapshot = Collections.unmodifiableList(new ArrayList<>(finds.values()));
            revision++;
        }
        scheduleSave();
    }

    private void scheduleSave() {
        if(saveQueued.compareAndSet(false, true)) {
            WRITER.execute(() -> {
                // Cleared before writing, so a change made during the write queues the next one.
                saveQueued.set(false);
                save();
            });
        }
    }

    /** Write the file now. Used on shutdown, so the last change lands even if the writer is mid-queue. */
    public void flush() {
        save();
    }

    private void save() {
        synchronized(saveLock) {
            try {
                NFileUtils.writeAtomically(path, serialize());
            } catch(IOException e) {
                System.err.println("[Forage] could not save " + path + ": " + e.getMessage());
            }
        }
    }

    private synchronized String serialize() {
        JSONObject main = new JSONObject();
        main.put("version", 1);
        JSONArray arr = new JSONArray();
        for(ForageFind f : finds.values())
            arr.put(f.toJson());
        main.put("finds", arr);
        main.put("pendingUpsert", new JSONArray(pendingUpsert.keySet()));
        main.put("pendingDelete", new JSONArray(pendingDelete.keySet()));
        return main.toString();
    }

    private void load() {
        String content = NFileUtils.readWithBackupFallback(path);
        if(content == null || content.isEmpty())
            return;
        synchronized(this) {
            try {
                JSONObject main = new JSONObject(content);
                JSONArray arr = main.optJSONArray("finds");
                if(arr != null) {
                    for(int i = 0; i < arr.length(); i++) {
                        ForageFind f = ForageFind.fromJson(arr.getJSONObject(i));
                        finds.put(f.id, f);
                    }
                }
                JSONArray pu = main.optJSONArray("pendingUpsert");
                if(pu != null) {
                    for(int i = 0; i < pu.length(); i++)
                        pendingUpsert.put(pu.getString(i), ++generation);
                }
                JSONArray pd = main.optJSONArray("pendingDelete");
                if(pd != null) {
                    for(int i = 0; i < pd.length(); i++)
                        pendingDelete.put(pd.getString(i), ++generation);
                }
            } catch(JSONException e) {
                System.err.println("[Forage] could not read " + path + ": " + e.getMessage());
            }
            snapshot = Collections.unmodifiableList(new ArrayList<>(finds.values()));
            revision++;
        }
    }
}
