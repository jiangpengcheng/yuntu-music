package io.github.yuntumusic.direct;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import java.util.concurrent.Semaphore;

/** One Looper owns every native call for one player, including creation and release. */
final class AsyncPlayer {
  interface Factory {
    MediaPlayer create();
  }

  interface Listener {
    void prepared();

    void started();

    void completed();

    void buffering(boolean active);

    void error(String message);
  }

  // A wedged native release cannot be interrupted safely. Bound abandoned workers/resources.
  private static final Semaphore SLOTS = new Semaphore(3);
  private final Handler main = new Handler(Looper.getMainLooper());
  private final HandlerThread thread;
  private final Handler worker;
  private final Context context;
  private final Listener listener;
  private final Factory factory;
  private volatile boolean closed, released;
  private volatile int position, duration;
  private MediaPlayer media;
  private boolean prepared;
  private volatile String operation = "create";

  AsyncPlayer(Context context, Listener listener) {
    this(
        context,
        listener,
        new Factory() {
          public MediaPlayer create() {
            return new MediaPlayer();
          }
        });
  }

  AsyncPlayer(Context context, Listener listener, Factory factory) {
    if (!SLOTS.tryAcquire()) throw new IllegalStateException("系统播放器未响应，请稍后重试；持续异常时请强行停止应用后重开");
    this.context = context.getApplicationContext();
    this.listener = listener;
    this.factory = factory;
    thread = new HandlerThread("YuntuPlayer");
    thread.start();
    worker = new Handler(thread.getLooper());
  }

  private interface Task {
    void run() throws Exception;
  }

  private void submit(final String name, final Task task) {
    if (closed) return;
    worker.post(
        new Runnable() {
          public void run() {
            if (closed) return;
            operation = name;
            try {
              task.run();
            } catch (Exception e) {
              Log.w(
                  "YuntuPlayback",
                  "player_operation_failed operation="
                      + name
                      + " type="
                      + e.getClass().getSimpleName());
              emit(
                  new Runnable() {
                    public void run() {
                      listener.error("音频播放失败，请重试或切换标准音质");
                    }
                  });
            }
          }
        });
  }

  private void emit(final Runnable event) {
    if (closed) return;
    main.post(
        new Runnable() {
          public void run() {
            if (!closed) event.run();
          }
        });
  }

  void prepare(final String url) {
    submit(
        "prepare",
        new Task() {
          public void run() throws Exception {
            media = factory.create();
            media.setAudioStreamType(AudioManager.STREAM_MUSIC);
            media.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK);
            media.setOnPreparedListener(
                new MediaPlayer.OnPreparedListener() {
                  public void onPrepared(MediaPlayer p) {
                    if (closed) return;
                    prepared = true;
                    emit(
                        new Runnable() {
                          public void run() {
                            listener.prepared();
                          }
                        });
                    worker.post(poll);
                  }
                });
            media.setOnCompletionListener(
                new MediaPlayer.OnCompletionListener() {
                  public void onCompletion(MediaPlayer p) {
                    emit(
                        new Runnable() {
                          public void run() {
                            listener.completed();
                          }
                        });
                  }
                });
            media.setOnErrorListener(
                new MediaPlayer.OnErrorListener() {
                  public boolean onError(MediaPlayer p, final int what, final int extra) {
                    emit(
                        new Runnable() {
                          public void run() {
                            listener.error("音频播放失败 (" + what + "/" + extra + ")，请重试或切换标准音质");
                          }
                        });
                    return true;
                  }
                });
            media.setOnInfoListener(
                new MediaPlayer.OnInfoListener() {
                  public boolean onInfo(MediaPlayer p, int what, int extra) {
                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START
                        || what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                      final boolean active = what == MediaPlayer.MEDIA_INFO_BUFFERING_START;
                      emit(
                          new Runnable() {
                            public void run() {
                              listener.buffering(active);
                            }
                          });
                    }
                    return false;
                  }
                });
            media.setDataSource(context, Uri.parse(url));
            if (!closed) media.prepareAsync();
          }
        });
  }

  private final Runnable poll =
      new Runnable() {
        public void run() {
          if (closed || !prepared) return;
          operation = "progress";
          try {
            position = media.getCurrentPosition();
            duration = media.getDuration();
          } catch (Exception ignored) {
          }
          if (!closed) worker.postDelayed(this, 500);
        }
      };

  int position() {
    return position;
  }

  int duration() {
    return duration;
  }

  void start() {
    submit(
        "start",
        new Task() {
          public void run() {
            media.setVolume(1f, 1f);
            media.start();
            emit(
                new Runnable() {
                  public void run() {
                    listener.started();
                  }
                });
          }
        });
  }

  void pause() {
    submit(
        "pause",
        new Task() {
          public void run() {
            media.pause();
          }
        });
  }

  void seek(final int ms) {
    submit(
        "seek",
        new Task() {
          public void run() {
            media.seekTo(ms);
          }
        });
  }

  void volume(final float level) {
    submit(
        "volume",
        new Task() {
          public void run() {
            media.setVolume(level, level);
          }
        });
  }

  void close() {
    if (closed) return;
    closed = true; // Reject queued events immediately, before any blocking native call.
    worker.removeCallbacksAndMessages(null);
    main.removeCallbacksAndMessages(null);
    final long began = SystemClock.elapsedRealtime();
    final Runnable slow =
        new Runnable() {
          public void run() {
            if (!released)
              Log.w(
                  "YuntuPlayback",
                  "player_release_slow operation="
                      + operation
                      + " duration_ms="
                      + (SystemClock.elapsedRealtime() - began));
          }
        };
    main.postDelayed(slow, 5000);
    worker.post(
        new Runnable() {
          public void run() {
            operation = "release";
            try {
              if (media != null) media.release();
            } catch (Exception ignored) {
            } finally {
              media = null;
              released = true;
              main.removeCallbacks(slow);
              SLOTS.release();
              thread.quit();
            }
          }
        });
  }
}
