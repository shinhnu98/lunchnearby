package io.github.shinhnu98.lunchnearby;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Visit state for the places from the current spin session, kept in SharedPreferences so it survives the app being closed.
 * Same rules as the web page: within IN m = arrived; after STAY+ minutes, OUT+ m away for GONE = left.
 * The first place left is a visit; after that the others are only suggested ("q": ask "did you visit?") until AFTER has passed.
 */
final class VisitStore {
    static final double IN = 60, OUT = 150;
    static final long STAY = 5 * 60_000L, GONE = 60_000L, WIN = 3 * 3_600_000L, MAX = 5 * 3_600_000L, AFTER = 2 * 3_600_000L, KEEP = 24 * 3_600_000L;

    private VisitStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("visits", Context.MODE_PRIVATE);
    }

    private static JSONObject load(Context c) {
        try {
            return new JSONObject(prefs(c).getString("t", "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static void store(Context c, JSONObject t) {
        prefs(c).edit().putString("t", t.toString()).apply();
    }

    static String lang(Context c) {
        return prefs(c).getString("lang", "ko");
    }

    /** Replace the watched picks with the page's current list, keeping progress already made. */
    static synchronized void setTargets(Context c, String json, String lang) {
        JSONObject old = load(c), now = new JSONObject();
        long t = System.currentTimeMillis();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject x = a.getJSONObject(i);
                String k = String.valueOf(x.getLong("ts"));
                JSONObject o = old.optJSONObject(k);
                if (o == null) {
                    o = new JSONObject();
                    o.put("ts", x.getLong("ts"));
                    o.put("arr", x.optLong("arr", 0));
                }
                o.put("la", x.getDouble("la"));
                o.put("lo", x.getDouble("lo"));
                o.put("name", x.optString("name", ""));
                o.put("exp", x.optLong("exp", 0));
                if (x.optInt("q") == 1) o.put("q", 1);
                now.put(k, o);
            }
            /* finished visits stay a day so the page can still pick them up */
            for (Iterator<String> it = old.keys(); it.hasNext(); ) {
                String k = it.next();
                JSONObject o = old.getJSONObject(k);
                if (!now.has(k) && o.optLong("dep") > 0 && t - o.optLong("dep") < KEEP) now.put(k, o);
            }
        } catch (JSONException e) {
            return;
        }
        store(c, now);
        prefs(c).edit().putString("lang", "en".equals(lang) ? "en" : "ko").apply();
    }

    static synchronized String state(Context c) {
        JSONObject t = load(c);
        JSONArray out = new JSONArray();
        try {
            for (Iterator<String> it = t.keys(); it.hasNext(); ) {
                JSONObject o = t.getJSONObject(it.next());
                out.put(new JSONObject().put("ts", o.getLong("ts")).put("arr", o.optLong("arr")).put("dep", o.optLong("dep")));
            }
        } catch (JSONException ignored) {
        }
        return out.toString();
    }

    private static boolean live(JSONObject o, long t) {
        if (o.optLong("dep") > 0) return false;
        long arr = o.optLong("arr"), exp = o.optLong("exp");
        return arr > 0 ? t - arr < MAX : exp > 0 ? t < exp : t - o.optLong("ts") < WIN;
    }

    /** Picks still being watched (not visited yet, not expired). */
    static synchronized List<JSONObject> active(Context c) {
        JSONObject t = load(c);
        List<JSONObject> r = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Iterator<String> it = t.keys(); it.hasNext(); ) {
            JSONObject o = t.optJSONObject(it.next());
            if (o != null && live(o, now)) r.add(o);
        }
        return r;
    }

    static final class Change {
        boolean changed;
        final List<JSONObject> visited = new ArrayList<>();
    }

    static synchronized Change onLocation(Context c, double la, double lo, float acc) {
        Change ch = new Change();
        JSONObject t = load(c);
        long now = System.currentTimeMillis();
        float[] d = new float[1];
        try {
            List<JSONObject> all = new ArrayList<>();
            for (Iterator<String> it = t.keys(); it.hasNext(); ) all.add(t.getJSONObject(it.next()));
            for (JSONObject o : all) {
                if (!live(o, now)) continue;
                Location.distanceBetween(la, lo, o.getDouble("la"), o.getDouble("lo"), d);
                long arr = o.optLong("arr");
                if (arr == 0) {
                    if (d[0] <= IN + Math.min(acc, 50) / 2) {
                        o.put("arr", now);
                        ch.changed = true;
                    }
                } else if (d[0] <= OUT) {
                    o.remove("out");
                } else if (o.optLong("out") == 0) {
                    o.put("out", now);
                } else if (now - o.getLong("out") >= GONE) {
                    long out = o.getLong("out");
                    o.remove("out");
                    if (out - arr >= STAY) {
                        o.put("dep", out);
                        ch.visited.add(o);
                        if (o.optInt("q") == 0) /* first visit of the session: the rest become "did you visit?" */
                            for (JSONObject x : all)
                                if (x != o && x.optLong("dep") == 0) {
                                    x.put("q", 1);
                                    x.put("exp", out + AFTER);
                                }
                    } else {
                        o.put("arr", 0); /* a quick walk-by doesn't count */
                    }
                    ch.changed = true;
                }
            }
        } catch (JSONException ignored) {
        }
        store(c, t);
        return ch;
    }
}
