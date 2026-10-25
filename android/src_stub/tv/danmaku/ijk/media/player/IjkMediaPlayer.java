package tv.danmaku.ijk.media.player;

import android.content.Context;
import android.net.Uri;
import android.view.Surface;
import android.view.SurfaceHolder;
import java.util.Map;

/** Compile-time stub only. Real class provided by engine dex (classes2.dex). */
public class IjkMediaPlayer implements IMediaPlayer {
    public static final int OPT_CATEGORY_FORMAT = 1;
    public static final int OPT_CATEGORY_CODEC = 2;
    public static final int OPT_CATEGORY_SWS = 3;
    public static final int OPT_CATEGORY_PLAYER = 4;

    public IjkMediaPlayer() {}

    public void setDataSource(String path) {}
    public void setDataSource(String path, Map<String, String> headers) {}
    public void setDataSource(Context ctx, Uri uri, Map<String, String> headers) {}
    public void setOption(int category, String name, String value) {}
    public void setOption(int category, String name, long value) {}
    public void setKeepInBackground(boolean keep) {}

    public void setDisplay(SurfaceHolder sh) {}
    public void setSurface(Surface s) {}
    public void setDataSource(String path, Map<String, String> headers, String a) {}
    public void prepareAsync() {}
    public void start() {}
    public void pause() {}
    public void stop() {}
    public void reset() {}
    public void release() {}
    public boolean isPlaying() { return false; }
    public long getCurrentPosition() { return 0; }
    public long getDuration() { return 0; }
    public int getVideoWidth() { return 0; }
    public int getVideoHeight() { return 0; }
    public void setVolume(float l, float r) {}
    public void setAudioStreamType(int type) {}
    public void setOnPreparedListener(IMediaPlayer.OnPreparedListener l) {}
    public void setOnCompletionListener(IMediaPlayer.OnCompletionListener l) {}
    public void setOnErrorListener(IMediaPlayer.OnErrorListener l) {}
    public void setOnInfoListener(IMediaPlayer.OnInfoListener l) {}
    public void setOnVideoSizeChangedListener(IMediaPlayer.OnVideoSizeChangedListener l) {}
}
