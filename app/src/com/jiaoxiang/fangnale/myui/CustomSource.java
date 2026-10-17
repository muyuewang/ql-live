package com.jiaoxiang.fangnale.myui;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

/** User-provided live source list (txt / m3u / single url), stored in prefs as JSON. */
public class CustomSource {

    public static class Entry {
        public String name;
        public String url;
    }

    public static synchronized ArrayList<Entry> getEntries(Context ctx) {
        ArrayList<Entry> out = new ArrayList<Entry>();
        try {
            JSONArray arr = new JSONArray(ctx.getSharedPreferences("myui", 0).getString("custom", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Entry e = new Entry();
                e.name = o.optString("n", "");
                e.url = o.optString("u", "");
                if (e.url.length() > 0) out.add(e);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    public static synchronized void addEntry(Context ctx, String name, String url) {
        try {
            JSONArray arr = new JSONArray(ctx.getSharedPreferences("myui", 0).getString("custom", "[]"));
            JSONObject o = new JSONObject();
            o.put("n", name == null ? "自定义" : name);
            o.put("u", url);
            arr.put(o);
            ctx.getSharedPreferences("myui", 0).edit().putString("custom", arr.toString()).apply();
        } catch (Throwable ignored) {}
    }

    public static synchronized void removeEntry(Context ctx, int index) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences("myui", 0);
            if (index == -2) { // clear all
                sp.edit().putString("custom", "[]").apply();
                return;
            }
            JSONArray arr = new JSONArray(sp.getString("custom", "[]"));
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) if (i != index) out.put(arr.get(i));
            sp.edit().putString("custom", out.toString()).apply();
        } catch (Throwable ignored) {}
    }

    /** fetch and parse a remote txt/m3u list into channels (virtual group "自定义") */
    public static void fetch(final Context ctx, final String url, final Runnable done) {
        new Thread(new Runnable() {
            public void run() {
                ArrayList<Entry> list = new ArrayList<Entry>();
                HttpURLConnection conn = null;
                try {
                    URL u = new URL(url);
                    conn = (HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    String curGroup = "";
                    String line;
                    while ((line = r.readLine()) != null) {
                        line = line.trim();
                        if (line.length() == 0) continue;
                        if (line.contains("#genre#")) {
                            int a = line.indexOf(',');
                            int b = line.indexOf('#');
                            if (a > 0) curGroup = line.substring(0, a).trim();
                            else if (b > 0) curGroup = line.substring(0, b).replace("#", "").trim();
                            continue;
                        }
                        if (line.startsWith("#EXTINF")) {
                            int c1 = line.indexOf(',');
                            String title = c1 >= 0 ? line.substring(c1 + 1).trim() : "";
                            // next non-comment line should be url; peek via marker
                            Entry e = new Entry();
                            e.name = (curGroup.length() > 0 ? curGroup + " " : "") + title;
                            pendingTitle = e.name;
                            continue;
                        }
                        if (line.startsWith("#")) continue;
                        Entry e = new Entry();
                        if (pendingTitle != null && pendingTitle.length() > 0) {
                            e.name = pendingTitle;
                            pendingTitle = null;
                        } else {
                            int comma = line.indexOf(',');
                            e.name = comma > 0 ? line.substring(0, comma).trim() : ("频道" + (list.size() + 1));
                            if (comma > 0) line = line.substring(comma + 1).trim();
                        }
                        e.url = line;
                        if (e.url.startsWith("http") || e.url.startsWith("rtmp") || e.url.startsWith("rtsp"))
                            list.add(e);
                    }
                    r.close();
                } catch (Throwable ignored) {
                } finally {
                    if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
                }
                // overwrite "custom" list with fetched channels
                try {
                    JSONArray arr = new JSONArray();
                    for (Entry e : list) {
                        JSONObject o = new JSONObject();
                        o.put("n", e.name);
                        o.put("u", e.url);
                        arr.put(o);
                    }
                    ctx.getSharedPreferences("myui", 0).edit().putString("custom", arr.toString()).apply();
                } catch (Throwable ignored) {}
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                h.post(new Runnable() {
                    public void run() { done.run(); }
                });
            }
            private String pendingTitle = null;
        }).start();
    }

    /** convert stored entries to playable channels */
    public static ArrayList<ChannelStore.Channel> loadChannels(Context ctx) {
        ArrayList<ChannelStore.Channel> out = new ArrayList<ChannelStore.Channel>();
        for (Entry e : getEntries(ctx)) {
            ChannelStore.Channel ch = new ChannelStore.Channel();
            ch.name = e.name;
            ch.ename = "custom_" + e.url.hashCode();
            ch.urls = e.url;
            ch.prov = "自定义";
            ch.type = "";
            out.add(ch);
        }
        return out;
    }
}
