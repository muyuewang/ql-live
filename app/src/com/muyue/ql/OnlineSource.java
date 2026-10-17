package com.muyue.ql.myui;

import android.app.Activity;
import com.jiaoxiang.fangnale.bean.SourceListBean;
import com.jiaoxiang.fangnale.net.NetConstant;
import com.jiaoxiang.fangnale.utils.AesUtil;
import com.jiaoxiang.fangnale.utils.ApiUtils;
import com.jiaoxiang.fangnale.utils.NativeEncrypt;
import com.jiaoxiang.fangnale.utils.OkHttpClientManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetch fresh channel lines from the engine's online source API (same as original app).
 * Engine bean: liveSourceList/backSourceList hold the real urls;
 * liveSourceNameList/backSourceNameList hold the display labels.
 */
public class OnlineSource {

    public interface Callback {
        /** lines: playable raw urls; labels: parallel display names (may be empty strings) */
        void onLines(ArrayList<String> lines, ArrayList<String> labels);
        void onError();
    }

    public static void fetch(Activity act, String ename, final Callback cb) {
        try {
            String url = NetConstant.GET_SOURCE + ename;
            final String full = ApiUtils.addParams(act, url);
            OkHttpClientManager.getAsyn(full, new OkHttpClientManager.StringCallback() {
                public void onFailure(okhttp3.Request r, IOException e) {
                    cb.onError();
                }
                public void onResponse(String res) {
                    try {
                        String plain = AesUtil.decrypt2024(res, NativeEncrypt.getSalt());
                        SourceListBean b = SourceListBean.fromJSONData(plain);
                        ArrayList<String> lines = new ArrayList<String>();
                        ArrayList<String> labels = new ArrayList<String>();
                        if (b != null) {
                            appendPair(lines, labels, b.liveSourceList, b.liveSourceNameList);
                            appendPair(lines, labels, b.backSourceList, b.backSourceNameList);
                        }
                        if (lines.isEmpty()) cb.onError();
                        else cb.onLines(lines, labels);
                    } catch (Throwable t) {
                        cb.onError();
                    }
                }
            });
        } catch (Throwable t) {
            cb.onError();
        }
    }

    private static void appendPair(ArrayList<String> lines, ArrayList<String> labels,
                                   List urls, List names) {
        if (urls == null) return;
        for (int i = 0; i < urls.size(); i++) {
            Object uo = urls.get(i);
            if (uo == null) continue;
            String u = String.valueOf(uo).trim();
            if (u.length() == 0) continue;
            lines.add(u);
            String lb = (names != null && i < names.size() && names.get(i) != null)
                    ? String.valueOf(names.get(i)).trim() : "";
            labels.add(lb);
        }
    }

    /** best-effort line label via engine SourceName */
    public static String lineLabel(String raw) {
        try {
            String n = com.jiaoxiang.fangnale.utils.SourceName.whichName(raw);
            if (n != null && n.trim().length() > 0) return n.trim();
        } catch (Throwable ignored) {}
        return "线路";
    }

    // ---------------- channel groups (same as original app) ----------------

    public interface ProvsCallback {
        void onProvs(ArrayList<String> provs);
        void onError();
    }

    public interface GroupCallback {
        void onChannels(ArrayList<ChannelStore.Channel> list);
        void onError();
    }

    /** group list: 收藏/央视/卫视/高清/轮播/体育/影视/省份... (plain JSON array of strings) */
    public static void fetchProvs(final ProvsCallback cb) {
        OkHttpClientManager.getAsyn(NetConstant.GET_PROV, new OkHttpClientManager.StringCallback() {
            public void onFailure(okhttp3.Request r, IOException e) { cb.onError(); }
            public void onResponse(String res) {
                try {
                    org.json.JSONArray arr = new org.json.JSONArray(res);
                    ArrayList<String> out = new ArrayList<String>();
                    for (int i = 0; i < arr.length(); i++) {
                        String s = arr.optString(i, "").trim();
                        if (s.length() > 0) out.add(s);
                    }
                    if (out.isEmpty()) cb.onError(); else cb.onProvs(out);
                } catch (Throwable t) { cb.onError(); }
            }
        });
    }

    /**
     * channels of one group with current program (nowplaycontent).
     * Server returns full data only for signed requests (ApiUtils.addParams), same as original.
     */
    public static void fetchGroup(Activity act, String prov, final GroupCallback cb) {
        try {
            String url = NetConstant.GET_CHANNEL + java.net.URLEncoder.encode(prov, "UTF-8");
            final String full = ApiUtils.addParams(act, url);
            OkHttpClientManager.getAsyn(full, new OkHttpClientManager.StringCallback() {
                public void onFailure(okhttp3.Request r, IOException e) { cb.onError(); }
                public void onResponse(String res) {
                    try {
                        String plain = res.trim();
                        if (!plain.startsWith("[")) {
                            plain = AesUtil.decrypt2024(res, NativeEncrypt.getSalt());
                        }
                        org.json.JSONArray arr = new org.json.JSONArray(plain);
                        ArrayList<ChannelStore.Channel> out = new ArrayList<ChannelStore.Channel>();
                        for (int i = 0; i < arr.length(); i++) {
                            org.json.JSONObject o = arr.optJSONObject(i);
                            if (o == null) continue;
                            ChannelStore.Channel c = new ChannelStore.Channel();
                            c.ename = o.optString("ename", "");
                            c.name = o.optString("name", "");
                            c.nowplay = o.optString("nowplaycontent", "");
                            if (c.ename.length() == 0 || c.name.length() == 0) continue;
                            out.add(c);
                        }
                        if (out.isEmpty()) cb.onError(); else cb.onChannels(out);
                    } catch (Throwable t) { cb.onError(); }
                }
            });
        } catch (Throwable t) { cb.onError(); }
    }
}
