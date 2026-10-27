.class public Lcom/jiaoxiang/fangnale/jsparse/JsLayer;
.super Ljava/lang/Object;
.source "JsLayer.java"


# annotations
.annotation system Ldalvik/annotation/MemberClasses;
    value = {
        Lcom/jiaoxiang/fangnale/jsparse/JsLayer$ParserJsInterface;
    }
.end annotation


# static fields
.field static assetManager:Landroid/content/res/AssetManager;

.field static ctx:Landroid/content/Context;

.field static filesDirPath:Ljava/lang/String;

.field static jsEvaluator:Lcom/evgenii/jsevaluator/JsEvaluator;

.field private static scanner:Ljava/util/Scanner;


# direct methods
.method public constructor <init>()V
    .locals 0

    .line 57
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method public static assetFileToString(Ljava/lang/String;)Ljava/lang/String;
    .locals 0

    .line 103
    :try_start_0
    invoke-static {p0}, Lcom/jiaoxiang/fangnale/jsparse/JsLayer;->readFile(Ljava/lang/String;)Ljava/lang/String;

    move-result-object p0
    :try_end_0
    .catch Ljava/io/IOException; {:try_start_0 .. :try_end_0} :catch_0

    return-object p0

    :catch_0
    move-exception p0

    .line 106
    invoke-virtual {p0}, Ljava/io/IOException;->printStackTrace()V

    const/4 p0, 0x0

    return-object p0
.end method

.method public static evaluate(Ljava/lang/String;Lcom/evgenii/jsevaluator/interfaces/JsCallback;)V
    .locals 4

    const-string v0, "/91kds/parse/native_layer.js"

    .line 66
    sget-object v1, Lcom/jiaoxiang/fangnale/jsparse/JsLayer;->jsEvaluator:Lcom/evgenii/jsevaluator/JsEvaluator;

    if-eqz v1, :cond_2

    sget-object v1, Lcom/jiaoxiang/fangnale/jsparse/JsLayer;->assetManager:Landroid/content/res/AssetManager;

    if-nez v1, :cond_0

    goto :goto_1

    .line 71
    :cond_0
    :try_start_0
    new-instance v1, Ljava/io/File;

    new-instance v2, Ljava/lang/StringBuilder;

    invoke-direct {v2}, Ljava/lang/StringBuilder;-><init>()V

    invoke-static {}, Landroid/os/Environment;->getExternalStorageDirectory()Ljava/io/File;

    move-result-object v3

    invoke-virtual {v3}, Ljava/io/File;->getPath()Ljava/lang/String;

    move-result-object v3

    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v2, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v2

    invoke-direct {v1, v2}, Ljava/io/File;-><init>(Ljava/lang/String;)V

    .line 72
    invoke-virtual {v1}, Ljava/io/File;->exists()Z

    move-result v1

    if-eqz v1, :cond_1

    .line 73
    new-instance v1, Ljava/lang/StringBuilder;

    invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V

    invoke-static {}, Landroid/os/Environment;->getExternalStorageDirectory()Ljava/io/File;

    move-result-object v2

    invoke-virtual {v2}, Ljava/io/File;->getPath()Ljava/lang/String;

    move-result-object v2

    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v1, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v0

    invoke-static {v0}, Lcom/jiaoxiang/fangnale/jsparse/JsLayer;->assetFileToString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v0

    goto :goto_0

    .line 75
    :cond_1
    new-instance v0, Ljava/lang/StringBuilder;

    invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V

    sget-object v1, Lcom/jiaoxiang/fangnale/MyApplication;->dir:Ljava/lang/String;

    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    const-string v1, "/native_layer.js"

    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v0

    invoke-static {v0}, Lcom/jiaoxiang/fangnale/jsparse/JsLayer;->assetFileToString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v0

    .line 77
    :goto_0
    sget-object v1, Lcom/jiaoxiang/fangnale/jsparse/JsLayer;->jsEvaluator:Lcom/evgenii/jsevaluator/JsEvaluator;

    new-instance v2, Ljava/lang/StringBuilder;

    invoke-direct {v2}, Ljava/lang/StringBuilder;-><init>()V

    invoke-virtual {v2, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v2, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p0

    new-instance v0, Lcom/jiaoxiang/fangnale/jsparse/JsLayer$1;

    invoke-direct {v0, p1}, Lcom/jiaoxiang/fangnale/jsparse/JsLayer$1;-><init>(Lcom/evgenii/jsevaluator/interfaces/JsCallback;)V

    invoke-virtual {v1, p0, v0}, Lcom/evgenii/jsevaluator/JsEvaluator;->evaluate(Ljava/lang/String;Lcom/evgenii/jsevaluator/interfaces/JsCallback;)V
    :try_end_0
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0

    return-void

    :catch_0
    move-exception p0

    const-string v0, "internal error"

    .line 96
    invoke-interface {p1, v0}, Lcom/evgenii/jsevaluator/interfaces/JsCallback;->onError(Ljava/lang/String;)V

    .line 97
    invoke-static {p0}, Lcom/jiaoxiang/fangnale/utils/Logger;->e(Ljava/lang/Throwable;)V

    :cond_2
    :goto_1
    return-void
.end method

.method public static init(Landroid/content/Context;)V
    .locals 3

    # --- QL perf patch: 复用已建好的 JsEvaluator（WebView），避免每次解析都重建 ---
    # 原版每次 Parser.init 都 new 一个 WebView 且不销毁旧的：切台越多越卡、内存越涨。
    # 仅在 Context 未变时复用；Activity 重建（ctx 变化）仍走原路径保证安全。
    sget-object v0, Lcom/jiaoxiang/fangnale/jsparse/JsLayer;->jsEvaluator:Lcom/evgenii/jsevaluator/JsEvaluator;

    if-eqz v0, :cond_rebuild

    sget-object v0, Lcom/jiaoxiang/fangnale/jsparse/JsLayer;->ctx:Landroid/content/Context;

    if-ne v0, p0, :cond_rebuild

    const-string v0, "QLPERF"

    const-string v1, "   jslayer.reuse (复用 WebView)"

    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I

    return-void

    :cond_rebuild

    .line 123
    sput-object p0, Lcom/jiaoxiang/fangnale/jsparse/JsLayer;->ctx:Landroid/content/Context;

    .line 124
    new-instance v0, Lcom/evgenii/jsevaluator/JsEvaluator;

    invoke-direct {v0, p0}, Lcom/evgenii/jsevaluator/JsEvaluator;-><init>(Landroid/content/Context;)V

    sput-object v0, Lcom/jiaoxiang/fangnale/jsparse/JsLayer;->jsEvaluator:Lcom/evgenii/jsevaluator/JsEvaluator;

    .line 125
    invoke-virtual {v0}, Lcom/evgenii/jsevaluator/JsEvaluator;->getWebView()Landroid/webkit/WebView;

    move-result-object v0

    new-instance v1, Lcom/jiaoxiang/fangnale/jsparse/JsLayer$ParserJsInterface;

    invoke-direct {v1}, Lcom/jiaoxiang/fangnale/jsparse/JsLayer$ParserJsInterface;-><init>()V

    const-string v2, "client"

    invoke-virtual {v0, v1, v2}, Landroid/webkit/WebView;->addJavascriptInterface(Ljava/lang/Object;Ljava/lang/String;)V

    .line 126
    invoke-virtual {p0}, Landroid/content/Context;->getAssets()Landroid/content/res/AssetManager;

    move-result-object v0

    sput-object v0, Lcom/jiaoxiang/fangnale/jsparse/JsLayer;->assetManager:Landroid/content/res/AssetManager;

    .line 127
    new-instance v0, Ljava/lang/StringBuilder;

    invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V

    invoke-virtual {p0}, Landroid/content/Context;->getFilesDir()Ljava/io/File;

    move-result-object p0

    invoke-virtual {p0}, Ljava/io/File;->getPath()Ljava/lang/String;

    move-result-object p0

    invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    const-string p0, "/"

    invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p0

    sput-object p0, Lcom/jiaoxiang/fangnale/jsparse/JsLayer;->filesDirPath:Ljava/lang/String;

    return-void
.end method

.method private static readFile(Ljava/lang/String;)Ljava/lang/String;
    .locals 2
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/io/IOException;
        }
    .end annotation

    .line 114
    new-instance v0, Ljava/io/FileInputStream;

    invoke-direct {v0, p0}, Ljava/io/FileInputStream;-><init>(Ljava/lang/String;)V

    .line 118
    new-instance p0, Ljava/util/Scanner;

    const-string v1, "UTF-8"

    invoke-direct {p0, v0, v1}, Ljava/util/Scanner;-><init>(Ljava/io/InputStream;Ljava/lang/String;)V

    sput-object p0, Lcom/jiaoxiang/fangnale/jsparse/JsLayer;->scanner:Ljava/util/Scanner;

    const-string v0, "\\A"

    .line 119
    invoke-virtual {p0, v0}, Ljava/util/Scanner;->useDelimiter(Ljava/lang/String;)Ljava/util/Scanner;

    move-result-object p0

    invoke-virtual {p0}, Ljava/util/Scanner;->next()Ljava/lang/String;

    move-result-object p0

    return-object p0
.end method
