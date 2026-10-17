package com.jiaoxiang.fangnale.utils;

import java.io.IOException;

/** Compile-time stub only. Real class provided by engine dex. */
public class OkHttpClientManager {

    public interface StringCallback {
        void onFailure(okhttp3.Request request, IOException e);
        void onResponse(String response);
    }

    public static void getAsyn(String url, StringCallback cb) {}
}
