package io.github.yuntumusic.direct;

import android.app.*;
import android.content.*;
import android.media.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

/** Local synthetic audio only. Run on isolated emulator with overlay AppOp allow/deny. */
public class MediaOutputTest extends Instrumentation {
  interface Work {
    void run() throws Exception;
  }

  interface Check {
    boolean ok() throws Exception;
  }

  final ArrayList<String> passed = new ArrayList<String>();
  PlaybackService service;
  MainActivity activity;
  Context context;
  boolean denied;

  Object get(Object target, String key) throws Exception {
    Field f = target.getClass().getDeclaredField(key);
    f.setAccessible(true);
    return f.get(target);
  }

  void set(Object target, String key, Object value) throws Exception {
    Field f = target.getClass().getDeclaredField(key);
    f.setAccessible(true);
    f.set(target, value);
  }

  void ui(final Work work) {
    final Throwable[] error = {null};
    runOnMainSync(
        new Runnable() {
          public void run() {
            try {
              work.run();
            } catch (Throwable t) {
              error[0] = t;
            }
          }
        });
    if (error[0] != null) throw new AssertionError(error[0]);
  }

  void check(String name, final Check condition) throws Exception {
    long end = SystemClock.elapsedRealtime() + 12000;
    while (SystemClock.elapsedRealtime() < end) {
      final boolean[] ok = {false};
      ui(
          new Work() {
            public void run() throws Exception {
              ok[0] = condition.ok();
            }
          });
      if (ok[0]) {
        passed.add(name);
        return;
      }
      Thread.sleep(80);
    }
    throw new AssertionError(name + " status=" + (service == null ? "unbound" : service.status()));
  }

  Object floating() throws Exception {
    return get(service, "floatingPlayer");
  }

  View card() throws Exception {
    return (View) get(floating(), "card");
  }

  RemoteControlClient remote() throws Exception {
    return (RemoteControlClient) get(service, "remote");
  }

  long publishedPosition() throws Exception {
    return ((Long) get(remote(), "mPlaybackPositionMs")).longValue();
  }

  int publishedState() throws Exception {
    return ((Integer) get(remote(), "mPlaybackState")).intValue();
  }

  float publishedSpeed() throws Exception {
    return ((Float) get(remote(), "mPlaybackSpeed")).floatValue();
  }

  View label(View view, String desc) {
    if (desc.contentEquals(
        view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = label(group.getChildAt(i), desc);
        if (found != null) return found;
      }
    }
    return null;
  }

  void click(final String desc) {
    ui(
        new Work() {
          public void run() throws Exception {
            View v = label(card(), desc);
            if (v == null) throw new AssertionError(desc);
            v.performClick();
          }
        });
  }

  void showBackground() {
    ui(
        new Work() {
          public void run() {
            service.setUiVisible(false);
          }
        });
  }

  void foreground() {
    ui(
        new Work() {
          public void run() {
            service.setUiVisible(true);
          }
        });
  }

  void waitCard() throws Exception {
    check(
        "card visible in background",
        new Check() {
          public boolean ok() throws Exception {
            return card() != null && card().getWindowToken() != null;
          }
        });
  }

  void screenshot(String name) throws Exception {
    Thread.sleep(300);
    android.graphics.Bitmap image = getUiAutomation().takeScreenshot();
    java.io.FileOutputStream out = context.openFileOutput(name, Context.MODE_PRIVATE);
    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
    out.close();
  }

  void positionCases() throws Exception {
    check(
        "publishes nonzero current position and speed",
        new Check() {
          public boolean ok() throws Exception {
            return publishedPosition() > 1000
                && publishedState() == RemoteControlClient.PLAYSTATE_PLAYING
                && publishedSpeed() == 1f;
          }
        });
    ui(
        new Work() {
          public void run() {
            service.pause();
          }
        });
    check(
        "pause publishes zero speed and preserves position",
        new Check() {
          public boolean ok() throws Exception {
            return publishedSpeed() == 0f
                && publishedState() == RemoteControlClient.PLAYSTATE_PAUSED
                && publishedPosition() > 0;
          }
        });
    final long paused = publishedPosition();
    Thread.sleep(1500);
    check(
        "paused position does not advance",
        new Check() {
          public boolean ok() throws Exception {
            return Math.abs(publishedPosition() - paused) < 700;
          }
        });
    ui(
        new Work() {
          public void run() {
            service.seek(22000);
          }
        });
    check(
        "seek while paused updates published position",
        new Check() {
          public boolean ok() throws Exception {
            return publishedPosition() >= 21500
                && publishedPosition() < 24000
                && publishedSpeed() == 0f;
          }
        });
    ui(
        new Work() {
          public void run() throws Exception {
            ((RemoteControlClient.OnPlaybackPositionUpdateListener)
                    get(remote(), "mPositionUpdateListener"))
                .onPlaybackPositionUpdate(10000);
          }
        });
    check(
        "system seek callback reaches async player",
        new Check() {
          public boolean ok() throws Exception {
            return publishedPosition() >= 9500 && publishedPosition() < 12000;
          }
        });
    check(
        "system position query uses cached player progress",
        new Check() {
          public boolean ok() throws Exception {
            return ((RemoteControlClient.OnGetPlaybackPositionListener)
                        get(remote(), "mPositionProvider"))
                    .onGetPlaybackPosition()
                >= 9500;
          }
        });
    ui(
        new Work() {
          public void run() {
            service.play();
          }
        });
    check(
        "resume restores playing speed",
        new Check() {
          public boolean ok() throws Exception {
            return service.isPlaying() && publishedSpeed() == 1f;
          }
        });
    ui(
        new Work() {
          public void run() throws Exception {
            set(service, "buffering", true);
            Method m = PlaybackService.class.getDeclaredMethod("publishPlaybackPosition");
            m.setAccessible(true);
            m.invoke(service);
          }
        });
    check(
        "buffering publishes stationary state",
        new Check() {
          public boolean ok() throws Exception {
            return publishedSpeed() == 0f
                && publishedState() == RemoteControlClient.PLAYSTATE_BUFFERING;
          }
        });
    ui(
        new Work() {
          public void run() throws Exception {
            set(service, "buffering", false);
            service.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
          }
        });
    check(
        "focus loss publishes stationary state",
        new Check() {
          public boolean ok() throws Exception {
            return publishedSpeed() == 0f;
          }
        });
    ui(
        new Work() {
          public void run() {
            service.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
          }
        });
    check(
        "focus resume restores progress",
        new Check() {
          public boolean ok() throws Exception {
            return service.isPlaying() && publishedSpeed() == 1f;
          }
        });
    final long before = publishedPosition();
    showBackground();
    Thread.sleep(1800);
    check(
        "position updates without activity UI",
        new Check() {
          public boolean ok() throws Exception {
            return publishedPosition() > before + 500;
          }
        });
    foreground();
  }

  void overlayCases() throws Exception {
    showBackground();
    check(
        "overlay disabled by default",
        new Check() {
          public boolean ok() throws Exception {
            return card() == null;
          }
        });
    foreground();
    ui(
        new Work() {
          public void run() {
            service.setFloatingEnabled(true);
          }
        });
    check(
        "enabled overlay hidden in app",
        new Check() {
          public boolean ok() throws Exception {
            return card() == null;
          }
        });
    showBackground();
    if (denied) {
      check(
          "permission denial preserves playback and hides card",
          new Check() {
            public boolean ok() throws Exception {
              return !FloatingPlayer.allowed(context) && card() == null && service.isPlaying();
            }
          });
      return;
    }
    waitCard();
    check(
        "overlay text and progress match song",
        new Check() {
          public boolean ok() throws Exception {
            return ((TextView) get(floating(), "title")).getText().toString().contains("晨间")
                && ((ProgressBar) get(floating(), "progress")).getProgress() > 0;
          }
        });
    ui(
        new Work() {
          public void run() {
            service.seek(22000);
          }
        });
    check(
        "floating lyric follows seek",
        new Check() {
          public boolean ok() throws Exception {
            return ((TextView) get(floating(), "lyric")).getText().toString().equals("让喜欢的旋律陪在身边");
          }
        });
    ui(
        new Work() {
          public void run() {
            service.seek(6000);
          }
        });
    check(
        "floating lyric follows backward seek",
        new Check() {
          public boolean ok() throws Exception {
            return ((TextView) get(floating(), "lyric")).getText().toString().equals("沿着风的方向慢慢向前");
          }
        });
    click("暂停");
    check(
        "overlay pauses player",
        new Check() {
          public boolean ok() {
            return !service.isPlaying();
          }
        });
    waitCard();
    click("播放");
    check(
        "overlay resumes player",
        new Check() {
          public boolean ok() {
            return service.isPlaying();
          }
        });
    click("下一首");
    check(
        "overlay next updates song",
        new Check() {
          public boolean ok() throws Exception {
            return service.isPlaying()
                && "2".equals(service.current().id)
                && ((TextView) get(floating(), "title")).getText().toString().contains("沿途");
          }
        });
    click("上一首");
    check(
        "overlay previous updates song",
        new Check() {
          public boolean ok() {
            return service.isPlaying() && "1".equals(service.current().id);
          }
        });
    ui(
        new Work() {
          public void run() throws Exception {
            View drag = label(card(), "拖动卡片，点击打开云途");
            long now = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 100, 100, 0);
            drag.dispatchTouchEvent(down);
            down.recycle();
            MotionEvent move =
                MotionEvent.obtain(now, now + 100, MotionEvent.ACTION_MOVE, -4000, -4000, 0);
            drag.dispatchTouchEvent(move);
            move.recycle();
            MotionEvent up =
                MotionEvent.obtain(now, now + 150, MotionEvent.ACTION_UP, -4000, -4000, 0);
            drag.dispatchTouchEvent(up);
            up.recycle();
          }
        });
    check(
        "drag clamps and saves position",
        new Check() {
          public boolean ok() {
            return Api.prefs(context).getInt("floatX", -1) == 0
                && Api.prefs(context).getInt("floatY", -1) == 0;
          }
        });
    foreground();
    check(
        "foreground removes overlay",
        new Check() {
          public boolean ok() throws Exception {
            return card() == null;
          }
        });
    showBackground();
    waitCard();
    check(
        "saved location restored",
        new Check() {
          public boolean ok() throws Exception {
            return ((WindowManager.LayoutParams) get(floating(), "params")).x == 0;
          }
        });
    click("关闭悬浮卡片");
    check(
        "close disables overlay without stopping audio",
        new Check() {
          public boolean ok() throws Exception {
            return card() == null
                && !Api.prefs(context).getBoolean("floating", true)
                && service.isPlaying();
          }
        });
    ui(
        new Work() {
          public void run() {
            service.setFloatingEnabled(true);
          }
        });
    waitCard();
    ui(
        new Work() {
          public void run() {
            service.stopPlayback();
          }
        });
    check(
        "stop removes card and publishes stopped",
        new Check() {
          public boolean ok() throws Exception {
            return card() == null
                && publishedState() == RemoteControlClient.PLAYSTATE_STOPPED
                && publishedPosition() == 0
                && publishedSpeed() == 0f;
          }
        });
    Thread.sleep(1300);
    check(
        "timer never revives stopped media state",
        new Check() {
          public boolean ok() throws Exception {
            return publishedState() == RemoteControlClient.PLAYSTATE_STOPPED && card() == null;
          }
        });
  }

  public void onCreate(Bundle b) {
    super.onCreate(b);
    denied = b != null && "deny".equals(b.getString("mode"));
    start();
  }

  String lyricText() throws Exception {
    return ((TextView) get(floating(), "lyric")).getText().toString();
  }

  void lyricCases() throws Exception {
    final ArrayList<Track> tracks = new ArrayList<Track>();
    for (int id : new int[] {9001, 1, 9002, 9003})
      tracks.add(
          new Track(
              new JSONObject()
                  .put("id", "" + id)
                  .put("name", "歌词测试 " + id)
                  .put("duration", 60000)));
    ui(
        new Work() {
          public void run() {
            service.setListener(
                null); // Isolate the background card's network requests from Activity UI.
            service.playQueue(tracks, 0, false, "", 0, false);
            service.setUiVisible(false);
          }
        });
    waitCard();
    ui(
        new Work() {
          public void run() {
            service.select(1);
          }
        });
    check(
        "new song lyric appears",
        new Check() {
          public boolean ok() throws Exception {
            return service.isPlaying() && lyricText().equals("落日铺满安静的海面");
          }
        });
    Thread.sleep(1800);
    check(
        "late old lyric cannot overwrite new song",
        new Check() {
          public boolean ok() throws Exception {
            return !lyricText().contains("旧请求");
          }
        });
    ui(
        new Work() {
          public void run() {
            service.select(2);
          }
        });
    check(
        "no lyric keeps audio playing",
        new Check() {
          public boolean ok() throws Exception {
            return service.isPlaying() && lyricText().equals("暂无歌词");
          }
        });
    ui(
        new Work() {
          public void run() {
            service.select(3);
          }
        });
    check(
        "lyric failure keeps audio playing",
        new Check() {
          public boolean ok() throws Exception {
            return service.isPlaying() && lyricText().equals("歌词暂时不可用，点击重试");
          }
        });
    click("当前歌词，点击查看或重试");
    check(
        "tap retries failed lyric successfully",
        new Check() {
          public boolean ok() throws Exception {
            return service.isPlaying() && lyricText().equals("落日铺满安静的海面");
          }
        });
    ui(
        new Work() {
          public void run() {
            service.select(0);
            service.setUiVisible(true);
            service.select(2);
          }
        });
    Thread.sleep(1800);
    ui(
        new Work() {
          public void run() {
            service.setUiVisible(false);
            service.select(0);
          }
        });
    check(
        "hidden abandoned lyric can load again",
        new Check() {
          public boolean ok() throws Exception {
            return service.isPlaying() && lyricText().equals("旧请求歌词不能覆盖新歌曲");
          }
        });
    ui(
        new Work() {
          public void run() {
            service.setListener(activity);
          }
        });
  }

  public void onStart() {
    Bundle result = new Bundle();
    try {
      context = getTargetContext();
      FixtureTransport.install(context);
      Api.prefs(context)
          .edit()
          .putString("server", "http://10.0.2.2:3211")
          .putString("token", "local-emulator-test-token-00000001")
          .putString("quality", "standard")
          .putString("mode", "order")
          .putBoolean("bootStart", false)
          .putBoolean("autoPlay", false)
          .putBoolean("floating", false)
          .putBoolean("floatingLarge", false)
          .putBoolean("floatingLyrics", false)
          .commit();
      activity =
          (MainActivity)
              startActivitySync(
                  new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      check(
          "service binds",
          new Check() {
            public boolean ok() throws Exception {
              service = (PlaybackService) get(activity, "service");
              return service != null;
            }
          });
      final ArrayList<Track> tracks = new ArrayList<Track>();
      for (int i = 1; i <= 3; i++)
        tracks.add(
            new Track(
                new JSONObject()
                    .put("id", "" + i)
                    .put("name", i == 1 ? "晨间出发 · 测试音" : i == 2 ? "沿途风景 · 测试音" : "夜色归途 · 测试音")
                    .put("artist", "云途本地联调")
                    .put("duration", 60000)
                    .put("cover", "http://10.0.2.2:3211/cover.png")));
      ui(
          new Work() {
            public void run() {
              service.playQueue(tracks, 0, false, "", 0, false);
            }
          });
      check(
          "fixture playback starts",
          new Check() {
            public boolean ok() {
              return service.isPlaying();
            }
          });
      positionCases();
      overlayCases();
      if (!denied) {
        lyricCases();
        ui(
            new Work() {
              public void run() {
                service.setUiVisible(true);
                service.playQueue(tracks, 0, false, "", 0, false);
              }
            });
        check(
            "preview playback starts",
            new Check() {
              public boolean ok() {
                return service.isPlaying();
              }
            });
        ui(
            new Work() {
              public void run() {
                service.seek(22000);
                activity.moveTaskToBack(true);
              }
            });
        waitCard();
        Thread.sleep(1500);
        screenshot("media-floating.png");
      }
      result.putString(
          "stream",
          "\nPASS "
              + passed.size()
              + " media output checks ("
              + (denied ? "deny" : "allow")
              + ")\n"
              + passed
              + "\n");
      finish(Activity.RESULT_OK, result);
    } catch (Throwable t) {
      result.putString("stream", "\nFAIL after " + passed.size() + ": " + t + "\n" + passed + "\n");
      finish(Activity.RESULT_CANCELED, result);
    }
  }
}
