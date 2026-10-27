package com.jiaoxiang.fangnale.utils;

import android.app.Activity;

/** Compile-time stub only. Real class provided by engine dex. */
public class ApiUtils {
    public static String addParams(Activity act, String url) { return url; }

    /** 启动配置接口用的签名（tmKey + md5），原版 StartActivity 用它拼 get_status 请求。 */
    public static String addStartParams(Activity act, String url) { return url; }
}
