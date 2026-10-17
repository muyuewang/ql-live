package tv.danmaku.ijk.media.player;

import android.content.Context;
import android.net.Uri;
import android.view.Surface;
import android.view.SurfaceHolder;

/** Compile-time stub only. Real class provided by engine dex. */
public interface IMediaPlayer {
    int MEDIA_INFO_UNKNOWN = 1;
    int MEDIA_INFO_VIDEO_RENDERING_START = 3;
    int MEDIA_INFO_VIDEO_ROTATION_CHANGED = 10001;
    int MEDIA_INFO_BUFFERING_START = 701;
    int MEDIA_INFO_BUFFERING_END = 702;
    int MEDIA_ERROR_UNKNOWN = -10000;
    int MEDIA_ERROR_SERVER_DIED = -10004;

    void setDisplay(SurfaceHolder sh);
    void setSurface(Surface s);
    void setDataSource(String path) throws Exception;
    void setDataSource(Context ctx, Uri uri, java.util.Map<String, String> headers) throws Exception;
    void prepareAsync() throws IllegalStateException;
    void start();
    void pause();
    void stop();
    void reset();
    void release();
    boolean isPlaying();
    long getCurrentPosition();
    long getDuration();
    int getVideoWidth();
    int getVideoHeight();
    void setVolume(float left, float right);
    void setAudioStreamType(int type);
    void setOnPreparedListener(OnPreparedListener l);
    void setOnCompletionListener(OnCompletionListener l);
    void setOnErrorListener(OnErrorListener l);
    void setOnInfoListener(OnInfoListener l);
    void setOnVideoSizeChangedListener(OnVideoSizeChangedListener l);

    interface OnPreparedListener { void onPrepared(IMediaPlayer mp); }
    interface OnCompletionListener { void onCompletion(IMediaPlayer mp); }
    interface OnErrorListener { boolean onError(IMediaPlayer mp, int what, int extra); }
    interface OnInfoListener { boolean onInfo(IMediaPlayer mp, int what, int extra); }
    interface OnVideoSizeChangedListener { void onVideoSizeChanged(IMediaPlayer mp, int width, int height, int sarNum, int sarDen); }
}
