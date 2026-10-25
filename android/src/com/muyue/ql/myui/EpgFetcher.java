package com.muyue.ql.myui;

import android.content.Context;
import org.json.JSONArray;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/** Fetch TV program guide (EPG) from the engine's public API. */
public class EpgFetcher {

    public static class Program {
        public String title;
        public long start;
        public long end;
        public boolean now;
    }

    public interface Callback {
        void onResult(ArrayList<Program> list);
    }

    public static void fetch(final String ename, final Callback cb) {
        new Thread(new Runnable() {
            public void run() {
                ArrayList<Program> list = new ArrayList<Program>();
                HttpURLConnection conn = null;
                try {
                    // day must be a yyyy-MM-dd date, otherwise server returns placeholder data
                    String day = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                            .format(new java.util.Date());
                    URL u = new URL("http://sjapi.91kds.cn/api/get_epgall_by_ename.php?npw=1&ename=" + ename + "&day=" + day);
                    conn = (HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    r.close();
                    JSONArray arr = new JSONArray(sb.toString());
                    long now = System.currentTimeMillis() / 1000L;
                    long dayStart = now - (now % 86400);
                    org.json.JSONArray arr2 = new org.json.JSONArray(sb.toString());
                    for (int i = 0; i < arr2.length(); i++) {
                        org.json.JSONObject o = arr2.optJSONObject(i);
                        if (o == null) continue;
                        Program p = new Program();
                        p.title = o.optString("t", "");
                        p.start = o.optLong("st", 0);
                        p.end = o.optLong("et", 0);
                        if (p.title.length() == 0 || p.start <= 0) continue;
                        if (p.end < dayStart || p.start > dayStart + 172800) continue;
                        p.now = now >= p.start && now < p.end;
                        list.add(p);
                    }
                    Collections.sort(list, new Comparator<Program>() {
                        public int compare(Program a, Program b) {
                            return Long.compare(a.start, b.start);
                        }
                    });
                } catch (Throwable ignored) {
                } finally {
                    if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
                }
                final ArrayList<Program> result = list;
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                h.post(new Runnable() {
                    public void run() {
                        cb.onResult(result);
                    }
                });
            }
        }).start();
    }
}
