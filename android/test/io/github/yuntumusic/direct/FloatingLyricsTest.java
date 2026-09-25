package io.github.yuntumusic.direct;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.*;

/** Local emulator only; no real account, no production API. */
public final class FloatingLyricsTest extends MediaOutputTest {
  long downAt;
  View touched;

  TextView row(int i) throws Exception {
    return (TextView) get(floating(), i == 0 ? "lyric" : "nextLyric");
  }

  String lyricText() throws Exception {
    return (row(0).getTypeface().isBold() ? row(0) : row(1)).getText().toString();
  }

  void seek(final int ms) {
    ui(
        new Work() {
          public void run() {
            service.pause();
            service.seek(ms);
          }
        });
  }

  void expectRows(String name, final String top, final String bottom, final int active)
      throws Exception {
    check(
        name,
        new Check() {
          public boolean ok() throws Exception {
            return row(0).getText().toString().contains(top)
                && row(1).getText().toString().contains(bottom)
                && row(active).getTypeface().isBold()
                && !row(1 - active).getTypeface().isBold()
                && row(active).getCurrentTextColor() == OverlayAppearance.color(context, 2)
                && row(1 - active).getCurrentTextColor() == 0xffffd166;
          }
        });
  }

  WindowManager.LayoutParams params() throws Exception {
    return (WindowManager.LayoutParams) get(floating(), "params");
  }

  boolean centered() throws Exception {
    return params().x == ((Integer) get(floating(), "screenWidth") - params().width) / 2;
  }

  void tapOutsideWindow() throws Exception {
    final int[] clicks = {0};
    final Button[] button = {null};
    ui(
        new Work() {
          public void run() throws Exception {
            button[0] = new Button(activity);
            button[0].setText("下层点击测试");
            button[0].setOnClickListener(
                new View.OnClickListener() {
                  public void onClick(View v) {
                    clicks[0]++;
                  }
                });
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(100, 50);
            lp.topMargin = params().y;
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            ((android.view.ViewGroup) activity.getWindow().getDecorView()).addView(button[0], lp);
          }
        });
    Thread.sleep(300);
    final int[] location = new int[2];
    ui(
        new Work() {
          public void run() {
            button[0].getLocationOnScreen(location);
          }
        });
    screenshot("outside-touch-before.png");
    long time = SystemClock.uptimeMillis();
    for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
      MotionEvent e =
          MotionEvent.obtain(
              time, SystemClock.uptimeMillis(), action, location[0] + 40, location[1] + 25, 0);
      e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
      if (!getUiAutomation().injectInputEvent(e, true))
        throw new AssertionError("inject touch failed");
      e.recycle();
    }
    check(
        "outside adaptive window reaches underlying activity",
        new Check() {
          public boolean ok() {
            return clicks[0] == 1;
          }
        });
    ui(
        new Work() {
          public void run() {
            ((android.view.ViewGroup) button[0].getParent()).removeView(button[0]);
          }
        });
  }

  void touch(final int action, final float x, final float y) {
    ui(
        new Work() {
          public void run() throws Exception {
            if (action == MotionEvent.ACTION_DOWN) {
              downAt = SystemClock.uptimeMillis();
              touched = card();
            }
            MotionEvent event =
                MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, x, y, 0);
            touched.dispatchTouchEvent(event);
            event.recycle();
          }
        });
  }

  void mode(final boolean lyrics, final boolean full) {
    ui(
        new Work() {
          public void run() {
            Api.prefs(context)
                .edit()
                .putBoolean("floatingLyrics", lyrics)
                .putBoolean("floating", full)
                .apply();
            service.setFloatingEnabled(full);
          }
        });
  }

  void select(final int index) {
    ui(
        new Work() {
          public void run() {
            service.select(index);
          }
        });
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
          .putString("mode", "order")
          .putBoolean("bootStart", false)
          .putBoolean("autoPlay", false)
          .putBoolean("floating", false)
          .putBoolean("floatingLarge", false)
          .putBoolean("floatingLyrics", false)
          .putInt("lyricFloatTransparency", 0)
          .putInt(OverlayAppearance.NEXT_COLOR_KEY, 0xffffd166)
          .putInt("lyricFloatTextScale", 100)
          .putInt("lyricFloatTextColor", PlayerViews.INK)
          .remove("lyricFloatX")
          .remove("lyricFloatY")
          .putInt("floatX", 90)
          .putInt("floatY", 85)
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
      for (int id : new int[] {1, 9001, 9002, 9003})
        tracks.add(
            new Track(
                new JSONObject()
                    .put("id", "" + id)
                    .put("name", id == 1 ? "晨间出发 · 测试音" : "歌词测试 " + id)
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
          "baseline playback starts",
          new Check() {
            public boolean ok() {
              return service.isPlaying();
            }
          });
      showBackground();
      check(
          "overlay defaults off",
          new Check() {
            public boolean ok() throws Exception {
              return card() == null;
            }
          });
      mode(true, false);
      if (denied) {
        Thread.sleep(1500);
        check(
            "denied permission leaves playback responsive",
            new Check() {
              public boolean ok() throws Exception {
                return card() == null && service.isPlaying();
              }
            });
        ui(
            new Work() {
              public void run() {
                service.seek(22000);
                service.pause();
              }
            });
        check(
            "denied overlay can pause and seek",
            new Check() {
              public boolean ok() {
                return !service.isPlaying() && service.position() >= 21500;
              }
            });
      } else {
        waitCard();
        check(
            "compact lyrics without artwork or playback controls",
            new Check() {
              public boolean ok() throws Exception {
                return (Boolean) get(floating(), "lyricsOnly")
                    && get(floating(), "cover") == null
                    && get(floating(), "progress") == null
                    && get(floating(), "play") == null
                    && ((LinearLayout) card()).getChildCount() == 2
                    && params().width < (Integer) get(floating(), "screenWidth")
                    && centered()
                    && row(0).getGravity() == Gravity.CENTER
                    && row(1).getGravity() == Gravity.CENTER
                    && FloatingPlayer.transparency(context, true) == 100
                    && (Integer) get(floating(), "appliedTransparency") == 100;
              }
            });
        check(
            "current and next line appear",
            new Check() {
              public boolean ok() throws Exception {
                return lyricText().equals("落日铺满安静的海面")
                    && ((TextView) get(floating(), "nextLyric"))
                        .getText()
                        .toString()
                        .equals("沿着风的方向慢慢向前");
              }
            });
        final int initialWidth = params().width;
        check(
            "width fits rendered text plus small padding",
            new Check() {
              public boolean ok() throws Exception {
                float text =
                    Math.max(
                        row(0).getPaint().measureText(row(0).getText().toString()),
                        row(1).getPaint().measureText(row(1).getText().toString()));
                return centered()
                    && params().width >= text
                    && params().width
                        < text + 50 * context.getResources().getDisplayMetrics().density;
              }
            });
        tapOutsideWindow();
        final JSONArray savedLines = (JSONArray) get(floating(), "lyricLines");
        ui(
            new Work() {
              public void run() throws Exception {
                StringBuilder longText = new StringBuilder();
                for (int i = 0; i < 100; i++) longText.append("很长的歌词");
                java.lang.reflect.Field f = FloatingPlayer.class.getDeclaredField("lyricLines");
                f.setAccessible(true);
                f.set(
                    floating(),
                    new JSONArray()
                        .put(new JSONObject().put("time", 0).put("text", longText.toString())));
                service.updateFloatingPlayer();
              }
            });
        check(
            "long lyrics cap width inside screen",
            new Check() {
              public boolean ok() throws Exception {
                int screen = (Integer) get(floating(), "screenWidth");
                return params().width < screen && params().width > screen * .9 && centered();
              }
            });
        ui(
            new Work() {
              public void run() throws Exception {
                java.lang.reflect.Field f = FloatingPlayer.class.getDeclaredField("lyricLines");
                f.setAccessible(true);
                f.set(floating(), savedLines);
                service.updateFloatingPlayer();
              }
            });
        check(
            "short lyrics shrink window again",
            new Check() {
              public boolean ok() throws Exception {
                return params().width == initialWidth && centered();
              }
            });
        seek(4900);
        ui(
            new Work() {
              public void run() {
                service.play();
              }
            });
        expectRows("natural playback hands current line to bottom", "城市的灯火", "风的方向", 1);
        seek(11000);
        expectRows("next transition hands current line to top", "城市的灯火", "蓝天", 0);
        seek(16000);
        expectRows("third transition preserves bottom current", "旋律", "蓝天", 1);
        seek(22000);
        expectRows("forward seek restores alternating rows", "旋律", "新的风景", 0);
        seek(55000);
        check(
            "song tail has no metadata fallback",
            new Check() {
              public boolean ok() throws Exception {
                return row(0).getText().length() == 0
                    && row(1).getText().toString().contains("听见旅途")
                    && params().width < initialWidth
                    && centered();
              }
            });
        seek(6000);
        expectRows("backward seek restores bottom current", "城市的灯火", "风的方向", 1);
        Thread.sleep(1100);
        expectRows("pause preserves both rows", "城市的灯火", "风的方向", 1);
        final int initialX = params().x, initialY = params().y;
        touch(MotionEvent.ACTION_DOWN, 100, 25);
        touch(MotionEvent.ACTION_MOVE, 240, 55);
        touch(MotionEvent.ACTION_UP, 240, 55);
        check(
            "drag moves immediately without opening app",
            new Check() {
              public boolean ok() throws Exception {
                return params().x == initialX
                    && params().y == initialY + 30
                    && !(Boolean) get(service, "uiVisible");
              }
            });
        touch(MotionEvent.ACTION_DOWN, 100, 25);
        touch(MotionEvent.ACTION_MOVE, -4000, -4000);
        touch(MotionEvent.ACTION_UP, -4000, -4000);
        check(
            "immediate drag clamps and persists separately",
            new Check() {
              public boolean ok() throws Exception {
                return centered()
                    && params().y == 0
                    && Api.prefs(context).getInt("lyricFloatX", -1) == params().x
                    && Api.prefs(context).getInt("lyricFloatY", -1) == 0
                    && Api.prefs(context).getInt("floatX", 0) == 90;
              }
            });
        check(
            "drag release does not open app",
            new Check() {
              public boolean ok() throws Exception {
                return !(Boolean) get(service, "uiVisible") && card().getAlpha() == 1f;
              }
            });
        foreground();
        showBackground();
        waitCard();
        check(
            "saved lyrics position restored",
            new Check() {
              public boolean ok() throws Exception {
                return centered() && params().y == 0;
              }
            });
        touch(MotionEvent.ACTION_DOWN, 100, 25);
        touch(MotionEvent.ACTION_CANCEL, 100, 25);
        check(
            "cancel does not open app",
            new Check() {
              public boolean ok() throws Exception {
                return card().getAlpha() == 1f && !(Boolean) get(service, "uiVisible");
              }
            });
        touch(MotionEvent.ACTION_DOWN, 100, 25);
        touch(MotionEvent.ACTION_POINTER_DOWN, 100, 25);
        touch(MotionEvent.ACTION_UP, 100, 25);
        check(
            "multi touch never opens app",
            new Check() {
              public boolean ok() throws Exception {
                return !(Boolean) get(service, "uiVisible");
              }
            });
        touch(MotionEvent.ACTION_DOWN, 100, 25);
        mode(false, false);
        mode(true, false);
        waitCard();
        check(
            "hide replaces touched window without changing opacity",
            new Check() {
              public boolean ok() throws Exception {
                return card().getAlpha() == 1f;
              }
            });
        mode(false, true);
        waitCard();
        check(
            "full card still available at its own location",
            new Check() {
              public boolean ok() throws Exception {
                return !(Boolean) get(floating(), "lyricsOnly")
                    && get(floating(), "cover") != null
                    && params().x == 90;
              }
            });
        mode(true, false);
        waitCard();
        check(
            "switch back releases full card resources",
            new Check() {
              public boolean ok() throws Exception {
                return get(floating(), "cover") == null && centered();
              }
            });
        select(1);
        Thread.sleep(150);
        select(0);
        Thread.sleep(1700);
        check(
            "old lyric response cannot overwrite current song",
            new Check() {
              public boolean ok() throws Exception {
                return service.isPlaying()
                    && lyricText().contains("落日")
                    && !lyricText().contains("旧请求");
              }
            });
        select(2);
        check(
            "no lyrics clears previous song",
            new Check() {
              public boolean ok() throws Exception {
                return service.isPlaying()
                    && "9002".equals(service.current().id)
                    && row(0).getText().length() == 0
                    && row(1).getText().length() == 0
                    && params().width == 1
                    && params().height == 1
                    && (params().flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0;
              }
            });
        select(3);
        check(
            "lyric failure leaves music playing",
            new Check() {
              public boolean ok() throws Exception {
                return service.isPlaying()
                    && (Boolean) get(floating(), "lyricFailed")
                    && row(0).getText().length() == 0
                    && row(1).getText().length() == 0
                    && params().width == 1
                    && params().height == 1
                    && (params().flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0;
              }
            });
        select(0);
        check(
            "valid lyrics restore touchable compact window",
            new Check() {
              public boolean ok() throws Exception {
                return lyricText().contains("落日")
                    && params().width > 1
                    && centered()
                    && (params().flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0;
              }
            });
        // Move the actual Activity to background before checking a real single-tap launch.
        ui(
            new Work() {
              public void run() {
                activity.moveTaskToBack(true);
              }
            });
        waitCard();
        touch(MotionEvent.ACTION_DOWN, 100, 25);
        touch(MotionEvent.ACTION_UP, 100, 25);
        check(
            "single tap enters app after lyrics recover",
            new Check() {
              public boolean ok() throws Exception {
                return (Boolean) get(service, "uiVisible") && card() == null;
              }
            });
        ui(
            new Work() {
              public void run() {
                service.stopPlayback();
                service.setUiVisible(false);
              }
            });
        Thread.sleep(1300);
        check(
            "stop removes window and timer never revives it",
            new Check() {
              public boolean ok() throws Exception {
                return card() == null;
              }
            });
        ui(
            new Work() {
              public void run() {
                service.playQueue(tracks, 0, false, "", 0, false);
                service.setUiVisible(false);
              }
            });
        waitCard();
        check(
            "restart keeps lyrics enabled",
            new Check() {
              public boolean ok() throws Exception {
                return service.isPlaying() && lyricText().contains("落日");
              }
            });
        mode(false, false);
        check(
            "disable removes overlay without stopping music",
            new Check() {
              public boolean ok() throws Exception {
                return card() == null && service.isPlaying();
              }
            });
        // Preview on emulator home, for visual inspection and physical touch follow-up.
        ui(
            new Work() {
              public void run() {
                Api.prefs(context)
                    .edit()
                    .putInt("lyricFloatX", 272)
                    .putInt("lyricFloatY", 410)
                    .apply();
                service.setUiVisible(true);
              }
            });
        mode(true, false);
        ui(
            new Work() {
              public void run() {
                service.seek(22000);
                service.pause();
                activity.moveTaskToBack(true);
              }
            });
        waitCard();
        Thread.sleep(1200);
        screenshot("floating-lyrics.png");
      }
      result.putString(
          "stream",
          "\nPASS "
              + passed.size()
              + " floating lyrics checks ("
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
