package com.jiaoxiang.fangnale.utils;

import android.content.Context;

/** Compile-time stub only. Real class provided by engine dex (classes3.dex). */
public class HtmlContentParser {
    public interface CallBack {
        void onError();
        void pauseVideo();
        void startVideo(String url);
    }

    public HtmlContentParser(Context ctx) {}

    public void addCallBack(CallBack cb) {}
    public boolean needSecondParse(String url) { return false; }
    public void cancelExistParser(String url) {}
    public void release() {}
}
