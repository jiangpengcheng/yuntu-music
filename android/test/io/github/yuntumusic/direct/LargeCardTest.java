package io.github.yuntumusic.direct;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import org.json.*;

/** Isolated emulator and fixture only; never run on a daily-use device. */
public final class LargeCardTest extends MediaOutputTest {
  int largeWidth, largeHeight;

  void require(String name, boolean value) {
    if (!value) throw new AssertionError(name);
    passed.add(name);
  }

  TextView[] lines() throws Exception {
    return (TextView[]) get(floating(), "largeLines");
  }

  String line(int i) throws Exception {
    return lines()[i].getText().toString();
  }

  void mode(final boolean large, final boolean lyrics) {
    ui(
        new Work() {
          public void run() {
            Api.prefs(context)
                .edit()
                .putBoolean("floatingLarge", large)
                .putBoolean("floatingLyrics", lyrics)
                .commit();
            service.setFloatingEnabled(!lyrics);
          }
        });
  }

  void seek(final int position) {
    ui(
        new Work() {
          public void run() {
            service.pause();
            service.seek(position);
          }
        });
  }

  void seed(File path) throws Exception {
    FileOutputStream out = new FileOutputStream(path);
    out.write("retired test log".getBytes("UTF-8"));
    out.close();
  }

  public void onStart() {
    Bundle result = new Bundle();
    try {
      context = getTargetContext();
      FixtureTransport.install(context);
      context.deleteFile("queue.json");
      Api.prefs(context)
          .edit()
          .putString("server", "http://10.0.2.2:3211")
          .putString("token", "local-emulator-test-token-00000001")
          .putString("quality", "standard")
          .putBoolean("bootStart", false)
          .putBoolean("autoPlay", false)
          .putBoolean("floating", true)
          .putBoolean("floatingLyrics", false)
          .putBoolean("floatingLarge", true)
          .putBoolean("overlayLogsRemoved", false)
          .putBoolean("overlayCarCompat", true)
          .putInt("floatX", 90)
          .putInt("floatY", 85)
          .putInt("lyricFloatX", 120)
          .putInt("lyricFloatY", 160)
          .remove("largeFloatX")
          .remove("largeFloatY")
          .putInt("floatTransparency", 4)
          .remove(OverlayAppearance.nextColorKey(1))
          .putInt("floatTextScale", 100)
          .putInt("largeFloatTextScale", 100)
          .putInt("floatTextColor", PlayerViews.INK)
          .putInt("largeFloatTextColor", PlayerViews.INK)
          .commit();
      final File current = new File(context.getFilesDir(), "overlay-diagnostics.jsonl");
      final File previous = new File(context.getFilesDir(), "overlay-diagnostics.previous.jsonl");
      final File exported =
          new File(context.getExternalFilesDir("diagnostics"), "yuntu-overlay-log.txt");
      final File keep = new File(context.getFilesDir(), "keep-fixture.txt");
      seed(current);
      seed(previous);
      seed(exported);
      seed(keep);
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
      check(
          "upgrade removes only retired diagnostic files",
          new Check() {
            public boolean ok() {
              return !current.exists()
                  && !previous.exists()
                  && !exported.exists()
                  && keep.exists()
                  && Api.prefs(context).getBoolean("overlayLogsRemoved", false);
            }
          });
      require(
          "cleanup preserves connection settings",
          "local-emulator-test-token-00000001".equals(Api.prefs(context).getString("token", "")));
      final ArrayList<Track> tracks = new ArrayList<Track>();
      for (int id : new int[] {1, 9002, 9003})
        tracks.add(
            new Track(
                new JSONObject()
                    .put("id", "" + id)
                    .put("name", "晨间出发 · 测试音")
                    .put("artist", "云途本地联调")
                    .put("duration", 60000)
                    .put("cover", "http://10.0.2.2:3211/cover.png")));
      ui(
          new Work() {
            public void run() {
              service.setListener(null);
              service.playQueue(tracks, 0, false, "", 0, false);
            }
          });
      check(
          "playback starts",
          new Check() {
            public boolean ok() {
              return service.isPlaying();
            }
          });
      showBackground();
      waitCard();
      seek(12000);
      check(
          "three lyric lines centered on current playback",
          new Check() {
            public boolean ok() throws Exception {
              return lines() != null
                  && lines().length == 3
                  && line(0).contains("风的方向")
                  && line(1).contains("城市的灯火")
                  && line(2).contains("蓝天");
            }
          });
      ui(
          new Work() {
            public void run() throws Exception {
              WindowManager.LayoutParams p = (WindowManager.LayoutParams) get(floating(), "params");
              require(
                  "large window compact and within screen",
                  p.width
                          == Math.min(
                              Math.round(340 * context.getResources().getDisplayMetrics().density),
                              (Integer) get(floating(), "screenWidth"))
                      && p.x >= 0
                      && p.y >= 0
                      && p.y + card().getHeight() <= (Integer) get(floating(), "screenHeight"));
              require(
                  "current lyric bold and brighter",
                  lines()[1].getTypeface().isBold()
                      && lines()[1].getCurrentTextColor() != lines()[0].getCurrentTextColor());
              require(
                  "large card shares card transparency",
                  FloatingPlayer.transparency(context, false) == 4);
              largeWidth = p.width;
              largeHeight = card().getHeight();
              View drag = label(card(), "拖动卡片，点击打开云途");
              long t = SystemClock.uptimeMillis();
              for (int action :
                  new int[] {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP
                  }) {
                float v = action == MotionEvent.ACTION_DOWN ? 100 : -4000;
                MotionEvent e = MotionEvent.obtain(t, t + 100, action, v, v, 0);
                drag.dispatchTouchEvent(e);
                e.recycle();
              }
            }
          });
      check(
          "large card position stored separately",
          new Check() {
            public boolean ok() {
              return Api.prefs(context).getInt("largeFloatX", -1) == 0
                  && Api.prefs(context).getInt("largeFloatY", -1) == 0
                  && Api.prefs(context).getInt("floatX", -1) == 90
                  && Api.prefs(context).getInt("lyricFloatX", -1) == 120;
            }
          });
      seek(55000);
      check(
          "song tail clears following lines",
          new Check() {
            public boolean ok() throws Exception {
              return line(1).contains("听见旅途") && line(2).equals("");
            }
          });
      seek(0);
      check(
          "seek backward clears previous lines",
          new Check() {
            public boolean ok() throws Exception {
              return line(0).equals("") && line(1).contains("落日") && line(2).contains("风的方向");
            }
          });
      click("下一首");
      check(
          "no-lyric track clears previous lyric rows",
          new Check() {
            public boolean ok() throws Exception {
              return "9002".equals(service.current().id)
                  && line(1).contains("暂无歌词")
                  && line(0).equals("")
                  && line(2).equals("");
            }
          });
      click("下一首");
      check(
          "lyric error displayed in center",
          new Check() {
            public boolean ok() throws Exception {
              return line(1).contains("歌词暂时不可用");
            }
          });
      click("当前歌词，点击查看或重试");
      check(
          "lyric retry recovers",
          new Check() {
            public boolean ok() throws Exception {
              return !line(1).contains("歌词暂时不可用") && !line(2).equals("");
            }
          });
      mode(false, false);
      waitCard();
      ui(
          new Work() {
            public void run() throws Exception {
              WindowManager.LayoutParams p = (WindowManager.LayoutParams) get(floating(), "params");
              require(
                  "small card retains old size and position",
                  lines() == null && p.width == largeWidth && p.x == 90 && p.y == 85);
              require(
                  "large card is taller at the same width",
                  largeHeight > card().getHeight() && p.width == largeWidth);
            }
          });
      mode(false, true);
      waitCard();
      ui(
          new Work() {
            public void run() throws Exception {
              require(
                  "compact lyrics remain available",
                  (Boolean) get(floating(), "lyricsOnly") && lines() == null);
            }
          });
      mode(true, false);
      waitCard();
      ui(
          new Work() {
            public void run() throws Exception {
              WindowManager.LayoutParams p = (WindowManager.LayoutParams) get(floating(), "params");
              require("large mode restores independent position", p.x == 0 && p.y == 0);
            }
          });
      click("关闭悬浮卡片");
      check(
          "closing large card hides without stopping audio",
          new Check() {
            public boolean ok() throws Exception {
              return card() == null
                  && service.isPlaying()
                  && !Api.prefs(context).getBoolean("floating", true);
            }
          });
      mode(true, false);
      showBackground();
      waitCard();
      ui(
          new Work() {
            public void run() {
              service.select(0);
            }
          });
      check(
          "preview track ready",
          new Check() {
            public boolean ok() {
              return service.isPlaying() && "1".equals(service.current().id);
            }
          });
      seek(12000);
      check(
          "large preview restored",
          new Check() {
            public boolean ok() throws Exception {
              return line(1).contains("城市的灯火");
            }
          });
      require(
          "diagnostic files not recreated",
          !current.exists() && !previous.exists() && !exported.exists());
      screenshot("large-card.png");
      result.putString(
          "stream", "\nPASS " + passed.size() + " large card checks\n" + passed + "\n");
      finish(Activity.RESULT_OK, result);
    } catch (Throwable t) {
      result.putString("stream", "\nFAIL after " + passed.size() + ": " + t + "\n" + passed + "\n");
      finish(Activity.RESULT_CANCELED, result);
    }
  }
}
