package com.stsvita.backend;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Preferences;
import java.util.HashMap;
import java.util.Map;

/**
 * In-memory only, and deliberately so: nothing the game persists comes
 * through here.
 *
 * StS keeps unlocks, stats and settings in its own JSON files under
 * ux0:data/stsvita/preferences/, written by SaveHelper/Prefs via
 * Gdx.files.local(), plain file IO that VitaFileHandle already serves. The
 * libGDX Preferences interface is only reached from
 * CardCrawlGame.migrateHelper/saveMigration, the one-time migration off the
 * old Properties-XML preferences, which finds nothing to migrate on a Vita.
 *
 * So implementing this class would not move any progress; a PC "preferences"
 * folder is copied across as-is (see tools/import_progress.py). Kept as a
 * conforming no-op so the migration path and any future caller cannot NPE.
 */
public class VitaPreferences implements Preferences {

    private final String name;
    private final Map<String, Object> values = new HashMap<>();

    public VitaPreferences(String name) {
        this.name = name;
    }

    @Override
    public Preferences putBoolean(String key, boolean val) {
        values.put(key, val);
        return this;
    }

    @Override
    public Preferences putInteger(String key, int val) {
        values.put(key, val);
        return this;
    }

    @Override
    public Preferences putLong(String key, long val) {
        values.put(key, val);
        return this;
    }

    @Override
    public Preferences putFloat(String key, float val) {
        values.put(key, val);
        return this;
    }

    @Override
    public Preferences putString(String key, String val) {
        values.put(key, val);
        return this;
    }

    @Override
    public Preferences put(Map<String, ?> vals) {
        values.putAll(vals);
        return this;
    }

    @Override
    public boolean getBoolean(String key) {
        return getBoolean(key, false);
    }

    @Override
    public int getInteger(String key) {
        return getInteger(key, 0);
    }

    @Override
    public long getLong(String key) {
        return getLong(key, 0L);
    }

    @Override
    public float getFloat(String key) {
        return getFloat(key, 0f);
    }

    @Override
    public String getString(String key) {
        return getString(key, "");
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        Object v = values.get(key);
        return v == null ? defValue : Boolean.parseBoolean(v.toString());
    }

    @Override
    public int getInteger(String key, int defValue) {
        Object v = values.get(key);
        return v == null ? defValue : Integer.parseInt(v.toString());
    }

    @Override
    public long getLong(String key, long defValue) {
        Object v = values.get(key);
        return v == null ? defValue : Long.parseLong(v.toString());
    }

    @Override
    public float getFloat(String key, float defValue) {
        Object v = values.get(key);
        return v == null ? defValue : Float.parseFloat(v.toString());
    }

    @Override
    public String getString(String key, String defValue) {
        Object v = values.get(key);
        return v == null ? defValue : v.toString();
    }

    @Override
    public Map<String, ?> get() {
        return new HashMap<>(values);
    }

    @Override
    public boolean contains(String key) {
        return values.containsKey(key);
    }

    @Override
    public void clear() {
        values.clear();
    }

    @Override
    public void remove(String key) {
        values.remove(key);
    }

    @Override
    public void flush() {
        // Nothing to write: real progress is flushed by Prefs, not by this.
        Gdx.app.debug("VitaPreferences", "flush(" + name + ") ignored");
    }
}
