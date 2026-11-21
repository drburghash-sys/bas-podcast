package com.bas.podcast;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Local account-free podcast database. */
public final class Library {
    private static final String PREFS = "bas_podcast_local_v1";
    private static final String KEY = "library";
    private Library() {}

    public static final class Feed {
        public String url = "", title = "", description = "", image = "", author = "";
        public JSONObject json() {
            JSONObject j = new JSONObject();
            try { j.put("url", url); j.put("title", title); j.put("description", description);
                j.put("image", image); j.put("author", author); } catch (Exception ignored) {}
            return j;
        }
        public static Feed from(JSONObject j) {
            Feed f = new Feed(); f.url = j.optString("url"); f.title = j.optString("title");
            f.description = j.optString("description"); f.image = j.optString("image");
            f.author = j.optString("author"); return f;
        }
    }
    public static final class Episode {
        public String key = "", feedUrl = "", feedTitle = "", title = "", description = "",
                image = "", audio = "", published = "", localFile = "";
        public long durationSeconds = 0, positionMs = 0, downloadId = -1, publishedMillis = 0;
        public boolean completed = false;
        public JSONObject json() {
            JSONObject j = new JSONObject();
            try {
                j.put("key", key); j.put("feedUrl", feedUrl); j.put("feedTitle", feedTitle);
                j.put("title", title); j.put("description", description); j.put("image", image);
                j.put("audio", audio); j.put("published", published);
                j.put("localFile", localFile); j.put("durationSeconds", durationSeconds);
                j.put("positionMs", positionMs); j.put("downloadId", downloadId);
                j.put("publishedMillis", publishedMillis);
                j.put("completed", completed);
            } catch (Exception ignored) {}
            return j;
        }
        public static Episode from(JSONObject j) {
            Episode e = new Episode();
            e.key = j.optString("key"); e.feedUrl = j.optString("feedUrl");
            e.feedTitle = j.optString("feedTitle"); e.title = j.optString("title");
            e.description = j.optString("description"); e.image = j.optString("image");
            e.audio = j.optString("audio"); e.published = j.optString("published");
            e.localFile = j.optString("localFile"); e.durationSeconds = j.optLong("durationSeconds");
            e.positionMs = j.optLong("positionMs"); e.downloadId = j.optLong("downloadId", -1);
            e.publishedMillis = j.optLong("publishedMillis");
            e.completed = j.optBoolean("completed"); return e;
        }
    }
    public static final class ParsedFeed {
        public Feed feed = new Feed();
        public final List<Episode> episodes = new ArrayList<>();
    }
    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
    private static JSONObject read(Context c) {
        try { return new JSONObject(prefs(c).getString(KEY, "{}")); }
        catch (Exception ex) { return new JSONObject(); }
    }
    private static void save(Context c, JSONObject root) {
        prefs(c).edit().putString(KEY, root.toString()).commit();
    }
    private static List<Feed> readFeeds(JSONObject root) {
        List<Feed> r = new ArrayList<>(); JSONArray arr = root.optJSONArray("feeds");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject j = arr.optJSONObject(i); if (j != null) r.add(Feed.from(j));
        }
        return r;
    }
    private static List<Episode> readEpisodes(JSONObject root) {
        List<Episode> r = new ArrayList<>(); JSONArray arr = root.optJSONArray("episodes");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject j = arr.optJSONObject(i); if (j != null) r.add(Episode.from(j));
        }
        return r;
    }
    private static void write(Context c, List<Feed> feeds, List<Episode> episodes) {
        JSONObject root = new JSONObject(); JSONArray a = new JSONArray(), b = new JSONArray();
        for (Feed f : feeds) a.put(f.json());
        for (Episode e : episodes) b.put(e.json());
        try { root.put("feeds", a); root.put("episodes", b); } catch (Exception ignored) {}
        save(c, root);
    }
    public static synchronized List<Feed> feeds(Context c) { return readFeeds(read(c)); }
    public static synchronized List<Episode> episodes(Context c) { return readEpisodes(read(c)); }
    public static synchronized Episode get(Context c, String key) {
        for (Episode e : episodes(c)) if (e.key.equals(key)) return e;
        return null;
    }
    public static synchronized List<Episode> merge(Context c, ParsedFeed result) {
        List<Feed> feeds = feeds(c);
        List<Episode> old = episodes(c);
        boolean subscribed = false;
        for (int i = 0; i < feeds.size(); i++) {
            if (feeds.get(i).url.equals(result.feed.url)) {
                feeds.set(i, result.feed); subscribed = true; break;
            }
        }
        if (!subscribed) feeds.add(0, result.feed);
        Map<String, Episode> byKey = new HashMap<>();
        for (Episode e : old) byKey.put(e.key, e);
        Set<String> seen = new HashSet<>();
        List<Episode> merged = new ArrayList<>(), fresh = new ArrayList<>();
        for (Episode e : result.episodes) {
            if (e.audio.isEmpty() || e.key.isEmpty() || !seen.add(e.key)) continue;
            Episode existing = byKey.remove(e.key);
            if (existing != null) {
                e.localFile = existing.localFile; e.downloadId = existing.downloadId;
                e.positionMs = existing.positionMs; e.completed = existing.completed;
            } else if (subscribed) { fresh.add(e); }
            merged.add(e);
        }
        for (Episode e : old) if (!seen.contains(e.key)) merged.add(e);
        merged.sort((a, b) -> Long.compare(b.publishedMillis, a.publishedMillis));
        if (merged.size() > 2000) merged = new ArrayList<>(merged.subList(0, 2000));
        write(c, feeds, merged);
        return fresh;
    }
    public static synchronized void unsubscribe(Context c, String url) {
        List<Feed> feeds = feeds(c); feeds.removeIf(f -> f.url.equals(url));
        List<Episode> eps = episodes(c); eps.removeIf(e -> e.feedUrl.equals(url));
        write(c, feeds, eps);
    }
    public static synchronized void updateDownload(Context c, String key, long id, String path) {
        mutate(c, key, e -> { e.downloadId = id; e.localFile = path; });
    }
    public static synchronized void clearDownload(Context c, String key) {
        mutate(c, key, e -> { e.downloadId = -1; e.localFile = ""; });
    }
    public static synchronized void saveProgress(Context c, String key, long ms, boolean completed) {
        mutate(c, key, e -> { e.positionMs = Math.max(0, ms); e.completed = completed; });
    }
    private interface EpisodeEdit { void change(Episode e); }
    private static void mutate(Context c, String key, EpisodeEdit edit) {
        List<Episode> eps = episodes(c); boolean found = false;
        for (Episode e : eps) if (e.key.equals(key)) { edit.change(e); found = true; break; }
        if (found) write(c, feeds(c), eps);
    }
    public static boolean setting(Context c, String name, boolean fallback) {
        return prefs(c).getBoolean(name, fallback);
    }
    public static void setSetting(Context c, String name, boolean on) {
        prefs(c).edit().putBoolean(name, on).apply();
    }
}
