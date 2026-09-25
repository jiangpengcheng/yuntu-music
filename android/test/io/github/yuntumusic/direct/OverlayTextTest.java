package io.github.yuntumusic.direct;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

public final class OverlayTextTest extends MediaOutputTest {
  void require(String n, boolean ok) {
    if (!ok) throw new AssertionError(n);
    passed.add(n);
  }

  TextView field(String key) throws Exception {
    return (TextView) get(floating(), key);
  }

  void mode(final int mode) {
    ui(
        new Work() {
          public void run() {
            Api.prefs(context)
                .edit()
                .putBoolean("floating", mode != 2)
                .putBoolean("floatingLarge", mode == 1)
                .putBoolean("floatingLyrics", mode == 2)
                .commit();
            service.updateFloatingPlayer();
          }
        });
  }

  void fits(String name) throws Exception {
    final String label = name;
    check(
        name,
        new Check() {
          public boolean ok() throws Exception {
            View card = card();
            if (card == null || card.getHeight() == 0) return false;
            WindowManager.LayoutParams p = (WindowManager.LayoutParams) get(floating(), "params");
            Point screen = new Point();
            ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay()
                .getSize(screen);
            if (p.y < 0
                || p.y + card.getHeight() > screen.y
                || p.x < 0
                || p.x + card.getWidth() > screen.x) return false;
            return textFits(card);
          }
        });
  }

  boolean textFits(View v) {
    if (v.getVisibility() != View.VISIBLE) return true;
    if (v instanceof TextView) {
      TextView t = (TextView) v;
      Paint.FontMetricsInt fm = t.getPaint().getFontMetricsInt();
      if (t.getHeight() < fm.bottom - fm.top) return false;
    }
    if (v instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) v;
      for (int i = 0; i < g.getChildCount(); i++) if (!textFits(g.getChildAt(i))) return false;
    }
    return true;
  }

  AlertDialog editorDialog(OverlayStyleEditor editor) throws Exception {
    Method m = OverlayStyleEditor.class.getDeclaredMethod("show");
    m.setAccessible(true);
    return (AlertDialog) m.invoke(editor);
  }

  public void onStart() {
    Bundle result = new Bundle();
    try {
      context = getTargetContext();
      context.deleteFile("queue.json");
      Api.prefs(context)
          .edit()
          .clear()
          .putBoolean("floating", true)
          .putBoolean("overlayCarCompat", true)
          .putString("quality", "standard")
          .putInt("fmCount", 30)
          .commit();
      FixtureTransport.install(context);
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
      final ArrayList<Track> queue = new ArrayList<Track>();
      queue.add(
          new Track(
              new JSONObject()
                  .put("id", "1")
                  .put("name", "文字样式测试")
                  .put("artist", "云途音乐")
                  .put("duration", 60000)));
      ui(
          new Work() {
            public void run() {
              service.playQueue(queue, 0, false, "", 0, false);
            }
          });
      check(
          "fixture playback",
          new Check() {
            public boolean ok() {
              return service.isPlaying();
            }
          });
      showBackground();
      waitCard();
      final float[] baseline = {0};
      final int[] lyricVersion = {0};
      ui(
          new Work() {
            public void run() throws Exception {
              require(
                  "upgrade default white", field("title").getCurrentTextColor() == PlayerViews.INK);
              require(
                  "upgrade default muted",
                  field("artist").getCurrentTextColor() == PlayerViews.MUTED);
              baseline[0] = field("title").getTextSize();
              lyricVersion[0] = (Integer) get(floating(), "lyricVersion");
              android.content.SharedPreferences.Editor e = Api.prefs(context).edit();
              OverlayAppearance.write(e, 0, 140, 0xff70d6b1);
              OverlayAppearance.write(e, 1, 160, 0xffffd166);
              OverlayAppearance.write(e, 2, 120, 0xff80bfff);
              e.commit();
              service.updateFloatingPlayer();
            }
          });
      check(
          "visible card rebuilds with changed style",
          new Check() {
            public boolean ok() throws Exception {
              return field("title").getCurrentTextColor() == 0xff70d6b1
                  && Math.abs(field("title").getTextSize() - baseline[0] * 1.4f) < .1;
            }
          });
      fits("small card enlarged text fits");
      ui(
          new Work() {
            public void run() throws Exception {
              require(
                  "style change preserves lyric request",
                  (Integer) get(floating(), "lyricVersion") == lyricVersion[0]);
              require(
                  "secondary color stays opaque",
                  Color.alpha(field("artist").getCurrentTextColor()) == 255);
            }
          });
      mode(1);
      waitCard();
      fits("large card max text fits screen");
      ui(
          new Work() {
            public void run() throws Exception {
              TextView[] rows = (TextView[]) get(floating(), "largeLines");
              require(
                  "large card uses own primary color",
                  rows[rows.length / 2].getCurrentTextColor() == 0xffffd166);
              require(
                  "large neighboring lyrics subdued",
                  rows[0].getCurrentTextColor() == OverlayAppearance.secondary(0xffffd166));
              require("current lyric remains bold", rows[rows.length / 2].getTypeface().isBold());
            }
          });
      ui(
          new Work() {
            public void run() throws Exception {
              Api.prefs(context)
                  .edit()
                  .putInt(OverlayAppearance.nextColorKey(1), 0xff70d6b1)
                  .commit();
              service.updateFloatingPlayer();
              TextView[] rows = (TextView[]) get(floating(), "largeLines");
              require(
                  "large card upcoming color independent",
                  rows[2].getCurrentTextColor() == 0xff70d6b1
                      && rows[1].getCurrentTextColor() == 0xffffd166
                      && rows[0].getCurrentTextColor() == OverlayAppearance.secondary(0xffffd166));
              require(
                  "large color setting leaves lyric strip default",
                  !Api.prefs(context).contains(OverlayAppearance.NEXT_COLOR_KEY));
            }
          });
      screenshot("text-large-card.png");
      mode(2);
      waitCard();
      fits("lyric strip enlarged text fits");
      ui(
          new Work() {
            public void run() throws Exception {
              require(
                  "lyric strip has independent color",
                  (field("lyric").getCurrentTextColor() == 0xff80bfff
                      || field("nextLyric").getCurrentTextColor() == 0xff80bfff));
              require(
                  "lyric strip has independent size",
                  Math.abs(
                          field("lyric").getTextSize()
                              - 18
                                  * 1.2f
                                  * context.getResources().getDisplayMetrics().scaledDensity)
                      < .1);
              Api.prefs(context).edit().putInt("lyricFloatTransparency", 100).commit();
              service.updateFloatingPlayer();
              require(
                  "transparent background keeps text opaque",
                  Color.alpha(field("lyric").getCurrentTextColor()) == 255);
            }
          });
      mode(0);
      waitCard();
      ui(
          new Work() {
            public void run() throws Exception {
              require(
                  "switch restores small card style",
                  field("title").getCurrentTextColor() == 0xff70d6b1);
            }
          });
      foreground();
      final OverlayStyleEditor[] edit = {null};
      final AlertDialog[] dialog = {null};
      ui(
          new Work() {
            public void run() throws Exception {
              edit[0] = new OverlayStyleEditor(activity, 1);
              edit[0].addTo(new LinearLayout(activity));
              dialog[0] = editorDialog(edit[0]);
              View root = dialog[0].getWindow().getDecorView();
              ((SeekBar) label(root, "悬浮文字字号比例")).setProgress(0);
              ((EditText) label(root, "自定义悬浮文字颜色")).setText("#123456");
              dialog[0].getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
              require(
                  "child cancel keeps draft", edit[0].scale == 160 && edit[0].color == 0xffffd166);
            }
          });
      ui(
          new Work() {
            public void run() throws Exception {
              dialog[0] = editorDialog(edit[0]);
              View root = dialog[0].getWindow().getDecorView();
              ((EditText) label(root, "自定义悬浮文字颜色")).setText("oops");
              dialog[0].getButton(AlertDialog.BUTTON_POSITIVE).performClick();
              require("invalid hex leaves dialog open", dialog[0].isShowing());
              ((EditText) label(root, "自定义悬浮文字颜色")).setText("aBc123");
              ((SeekBar) label(root, "悬浮文字字号比例")).setProgress(1);
              require(
                  "hex updates preview",
                  ((TextView) label(root, "悬浮文字主色预览")).getCurrentTextColor() == 0xffabc123);
            }
          });
      screenshot("text-style-editor.png");
      ui(
          new Work() {
            public void run() throws Exception {
              dialog[0].getButton(AlertDialog.BUTTON_POSITIVE).performClick();
              require(
                  "apply changes only draft",
                  edit[0].scale == 90
                      && edit[0].color == 0xffabc123
                      && OverlayAppearance.color(context, 1) == 0xffffd166);
              OverlayStyleEditor abandoned = new OverlayStyleEditor(activity, 1);
              require(
                  "parent cancel discards unsaved draft",
                  abandoned.scale == 160 && abandoned.color == 0xffffd166);
              android.content.SharedPreferences.Editor e = Api.prefs(context).edit();
              edit[0].write(e);
              e.commit();
              OverlayStyleEditor restored = new OverlayStyleEditor(activity, 1);
              require("save persists style", restored.scale == 90 && restored.color == 0xffabc123);
              require(
                  "save preserves other modes and playback settings",
                  OverlayAppearance.color(context, 0) == 0xff70d6b1
                      && OverlayAppearance.color(context, 2) == 0xff80bfff
                      && Api.prefs(context).getInt("fmCount", 0) == 30);
              dialog[0] = editorDialog(edit[0]);
              dialog[0].getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
              dialog[0].getButton(AlertDialog.BUTTON_POSITIVE).performClick();
              require(
                  "reset defaults remains draft",
                  edit[0].scale == 100
                      && edit[0].color == PlayerViews.INK
                      && OverlayAppearance.scale(context, 1) == 90);
            }
          });
      ui(
          new Work() {
            public void run() throws Exception {
              OverlayStyleEditor lyrics = new OverlayStyleEditor(activity, 2);
              lyrics.addTo(new LinearLayout(activity));
              AlertDialog d = editorDialog(lyrics);
              View view = d.getWindow().getDecorView();
              EditText next = (EditText) label(view, "下一句歌词颜色");
              next.setText("#FFD166");
              require(
                  "next color preview independent",
                  ((TextView) label(view, "悬浮文字辅助色预览")).getCurrentTextColor() == 0xffffd166
                      && ((TextView) label(view, "悬浮文字主色预览")).getCurrentTextColor() == 0xff80bfff);
              d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
              require("next color cancel drops draft", lyrics.nextColor == null);
              d = editorDialog(lyrics);
              next = (EditText) label(d.getWindow().getDecorView(), "下一句歌词颜色");
              next.setText("bad-color");
              d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
              require("invalid next color rejected", d.isShowing());
              next.setText("#FFD166");
              d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
              require(
                  "next color apply remains draft",
                  lyrics.nextColor == 0xffffd166
                      && !Api.prefs(context).contains(OverlayAppearance.NEXT_COLOR_KEY));
              android.content.SharedPreferences.Editor e = Api.prefs(context).edit();
              lyrics.write(e);
              e.commit();
              require(
                  "next color persisted independently",
                  new OverlayStyleEditor(activity, 2).nextColor == 0xffffd166
                      && OverlayAppearance.nextColor(context) == 0xffffd166
                      && OverlayAppearance.color(context, 2) == 0xff80bfff);
              d = editorDialog(lyrics);
              d.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
              d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
              require(
                  "next color reset remains draft",
                  lyrics.nextColor == null && OverlayAppearance.nextColor(context) == 0xffffd166);
              e = Api.prefs(context).edit();
              lyrics.write(e);
              e.commit();
              require(
                  "next color reset restores automatic",
                  !Api.prefs(context).contains(OverlayAppearance.NEXT_COLOR_KEY)
                      && OverlayAppearance.nextColor(context) == PlayerViews.MUTED);
              OverlayStyleEditor large = new OverlayStyleEditor(activity, 1);
              large.addTo(new LinearLayout(activity));
              d = editorDialog(large);
              next = (EditText) label(d.getWindow().getDecorView(), "下一句歌词颜色");
              next.setText("#AABBCC");
              d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
              require("card next color cancel drops draft", large.nextColor == 0xff70d6b1);
              d = editorDialog(large);
              next = (EditText) label(d.getWindow().getDecorView(), "下一句歌词颜色");
              next.setText("#AABBCC");
              d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
              e = Api.prefs(context).edit();
              large.write(e);
              e.commit();
              require(
                  "card next color saved independently",
                  new OverlayStyleEditor(activity, 1).nextColor == 0xffaabbcc
                      && OverlayAppearance.nextColor(context) == PlayerViews.MUTED);
              d = editorDialog(large);
              d.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
              d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
              e = Api.prefs(context).edit();
              large.write(e);
              e.commit();
              require(
                  "card next color reset restores automatic",
                  !Api.prefs(context).contains(OverlayAppearance.nextColorKey(1)));
              service.stopPlayback();
              activity.finish();
            }
          });
      // Clear just this test's appearance settings, so baseline regression suites retain defaults.
      android.content.SharedPreferences.Editor cleanup = Api.prefs(context).edit();
      for (int i = 0; i < 3; i++)
        cleanup.remove(OverlayAppearance.scaleKey(i)).remove(OverlayAppearance.colorKey(i));
      cleanup.commit();
      result.putInt("count", passed.size());
      result.putString("passed", passed.toString());
      finish(Activity.RESULT_OK, result);
    } catch (Throwable t) {
      result.putString("failure", android.util.Log.getStackTraceString(t));
      result.putString("passed", passed.toString());
      finish(Activity.RESULT_CANCELED, result);
    }
  }
}
