package tv.danmaku.ijk.media.player;

import android.content.Context;
import android.net.Uri;
import android.view.Surface;
import android.view.SurfaceHolder;
import java.util.Map;

/** Compile-time stub only. Real class provided by engine dex. */
public class AndroidMediaPlayer implements IMediaPlayer {
    public AndroidMediaPlayer() {}

    public void setDataSource(String path) {}
    public void setDataSource(Context ctx, Uri uri, Map<String, String> headers) {}

    public void setDisplay(SurfaceHolder sh) {}
    public void setSurface(Surface s) {}
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
