package com.muyue.ql.myui;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Channel data from engine-maintained utu.db (tvlist_table) + custom sources from prefs. */
public class ChannelStore {

    public static class Channel {
        public String name;
        public String ename;
        public String urls;
        public String prov;
        public String type;
        public String nowplay; // current program from online get_channel (may be null)

        public ArrayList<String> getLines() {
            ArrayList<String> out = new ArrayList<String>();
            if (urls == null) return out;
            for (String s : urls.split("#@")) {
                s = s.trim();
                if (s.length() > 0) out.add(s);
            }
            return out;
        }
    }

    private final Context ctx;
    private final LinkedHashMap<String, ArrayList<Channel>> groups = new LinkedHashMap<String, ArrayList<Channel>>();

    public ChannelStore(Context ctx) {
        this.ctx = ctx;
    }

    public void ensureSeeded() {
        try {
            File dbf = ctx.getDatabasePath("utu.db");
            if (dbf.exists()) return;
            File parent = dbf.getParentFile();
            if (parent != null) parent.mkdirs();
            InputStream in = ctx.getAssets().open("myui_utu.db");
            OutputStream out = new FileOutputStream(dbf);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            out.close();
        } catch (Throwable t) {
            // engine will populate the db itself
        }
    }

    /** @return true if any channel loaded */
    public synchronized boolean reload() {
        groups.clear();
        // group 0: custom sources
        ArrayList<Channel> custom = CustomSource.loadChannels(ctx);
        if (!custom.isEmpty()) groups.put("自定义", custom);
        SQLiteDatabase db = null;
        try {
            File dbf = ctx.getDatabasePath("utu.db");
            if (!dbf.exists()) return !groups.isEmpty();
            db = SQLiteDatabase.openDatabase(dbf.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            Cursor c = db.rawQuery(
                    "SELECT name, ename, urls, prov, types FROM tvlist_table " +
                    "WHERE urls IS NOT NULL AND urls != '' ORDER BY _id", null);
            while (c.moveToNext()) {
                Channel ch = new Channel();
                ch.name = c.getString(0);
                ch.ename = c.getString(1);
                ch.urls = c.getString(2);
                ch.prov = c.getString(3);
                ch.type = c.getString(4);
                if (ch.name == null || ch.name.length() == 0) continue;
                if (ch.prov == null || ch.prov.length() == 0) ch.prov = "其他";
                ArrayList<Channel> list = groups.get(ch.prov);
                if (list == null) {
                    list = new ArrayList<Channel>();
                    groups.put(ch.prov, list);
                }
                list.add(ch);
            }
            c.close();
        } catch (Throwable t) {
            // ignore, keep whatever we have
        } finally {
            if (db != null) try { db.close(); } catch (Throwable ignored) {}
        }
        int total = 0;
        for (ArrayList<Channel> l : groups.values()) total += l.size();
        return total > 0;
    }

    public synchronized ArrayList<String> getGroupNames() {
        return new ArrayList<String>(groups.keySet());
    }

    /** distinct prov list in db order (offline fallback for group tabs) */
    public synchronized ArrayList<String> getProvs() {
        ArrayList<String> out = new ArrayList<String>();
        for (String g : groups.keySet()) {
            if (!"自定义".equals(g)) out.add(g);
        }
        return out;
    }

    public synchronized ArrayList<Channel> getGroup(String name) {
        ArrayList<Channel> l = groups.get(name);
        return l == null ? new ArrayList<Channel>() : l;
    }

    public synchronized Channel findByEname(String ename) {
        if (ename == null) return null;
        for (ArrayList<Channel> l : groups.values()) {
            for (Channel ch : l) if (ename.equals(ch.ename)) return ch;
        }
        return null;
    }

    /** favorites stored as ename list in prefs, exposed as virtual group */
    public synchronized ArrayList<Channel> getFavorites() {
        ArrayList<Channel> out = new ArrayList<Channel>();
        try {
            JSONArray arr = new JSONArray(ctx.getSharedPreferences("myui", 0)
                    .getString("favs", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                Channel ch = findByEname(arr.optString(i));
                if (ch != null) out.add(ch);
            }
        } catch (Throwable ignored) {}
        return out;
    }
}
