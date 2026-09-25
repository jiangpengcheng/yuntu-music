package io.github.yuntumusic.direct;

import android.app.*;
import android.content.*;
import android.os.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

/** Real anonymous song playback in an isolated emulator; no fixture transport. */
public final class NativePlaybackTest extends MediaOutputTest {
  public void onStart() {
    Bundle out = new Bundle();
    try {
      context = getTargetContext();
      context.deleteFile("queue.json");
      context.getSharedPreferences("native-session", 0).edit().remove("cookie").commit();
      Field f = NativeApi.class.getDeclaredField("instance");
      f.setAccessible(true);
      f.set(null, new NativeApi(context, new NativeTransport(context)));
      Api.prefs(context)
          .edit()
          .clear()
          .putString("quality", "standard")
          .putBoolean("floating", true)
          .putBoolean("floatingLarge", true)
          .putString("mode", "one")
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
      tracks.add(
          new Track(
              NativeApi.obj(
                  "id",
                  "1417892367",
                  "name",
                  "两只老虎 · 直连播放测试",
                  "artist",
                  "公开音源测试",
                  "duration",
                  60000)));
      ui(
          new Work() {
            public void run() {
              service.playQueue(tracks, 0, false, "", 0, false);
            }
          });
      check(
          "real CDN playback starts",
          new Check() {
            public boolean ok() {
              return service.isPlaying() && service.position() > 1000;
            }
          });
      out.putLong("playing_pss_kb", Debug.getPss());
      out.putInt("thread_count", Thread.getAllStackTraces().size());
      ui(
          new Work() {
            public void run() {
              service.pause();
            }
          });
      check(
          "pause works",
          new Check() {
            public boolean ok() {
              return !service.isPlaying();
            }
          });
      ui(
          new Work() {
            public void run() {
              service.play();
            }
          });
      check(
          "resume works",
          new Check() {
            public boolean ok() {
              return service.isPlaying();
            }
          });
      showBackground();
      waitCard();
      check(
          "live lyrics loaded",
          new Check() {
            public boolean ok() throws Exception {
              String line =
                  ((android.widget.TextView[]) get(floating(), "largeLines"))
                      [1].getText()
                      .toString();
              return line.length() > 0
                  && !line.contains("加载")
                  && !line.contains("暂无")
                  && !line.contains("重试");
            }
          });
      out.putLong("overlay_pss_kb", Debug.getPss());
      long end = SystemClock.elapsedRealtime() + 120000;
      long max = 0;
      int samples = 0;
      while (SystemClock.elapsedRealtime() < end) {
        Thread.sleep(10000);
        max = Math.max(max, Debug.getPss());
        samples++;
        final long start = SystemClock.elapsedRealtime();
        ui(
            new Work() {
              public void run() {
                if (SystemClock.elapsedRealtime() - start > 1500)
                  throw new AssertionError("UI response stalled");
              }
            });
      }
      check(
          "still playing after 2 minute overlay soak",
          new Check() {
            public boolean ok() {
              return service.isPlaying();
            }
          });
      out.putLong("soak_max_pss_kb", max);
      out.putInt("soak_samples", samples);
      out.putString("passed", passed.toString());
      out.putInt("count", passed.size());
      ui(
          new Work() {
            public void run() {
              service.stopPlayback();
              activity.finish();
            }
          });
      finish(Activity.RESULT_OK, out);
    } catch (Throwable t) {
      out.putString("failure", android.util.Log.getStackTraceString(t));
      out.putString("passed", passed.toString());
      finish(Activity.RESULT_CANCELED, out);
    }
  }
}
