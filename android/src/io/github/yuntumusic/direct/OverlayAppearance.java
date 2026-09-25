package io.github.yuntumusic.direct;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

/** Three small preference pairs; no per-frame allocation or extra timer. */
final class OverlayAppearance {
  static final String[] NAMES = {"小号悬浮卡片", "大号悬浮卡片", "悬浮歌词"};

  static String scaleKey(int mode) {
    return prefix(mode) + "TextScale";
  }

  static String colorKey(int mode) {
    return prefix(mode) + "TextColor";
  }

  private static String prefix(int mode) {
    return mode == 2 ? "lyricFloat" : mode == 1 ? "largeFloat" : "float";
  }

  static int scale(Context c, int mode) {
    return Math.max(80, Math.min(160, Api.prefs(c).getInt(scaleKey(mode), 100)));
  }

  static int color(Context c, int mode) {
    return Api.prefs(c).getInt(colorKey(mode), PlayerViews.INK) | 0xff000000;
  }

  static final String NEXT_COLOR_KEY = "lyricFloatNextColor";

  static int nextColor(Context c) {
    return nextColor(c, 2);
  }

  static String nextColorKey(int mode) {
    return prefix(mode) + "NextColor";
  }

  static int nextColor(Context c, int mode) {
    return Api.prefs(c).getInt(nextColorKey(mode), secondary(color(c, mode))) | 0xff000000;
  }

  static int secondary(int color) {
    if (color == PlayerViews.INK) return PlayerViews.MUTED;
    return Color.rgb(
        (Color.red(color) + 20) / 2, (Color.green(color) + 20) / 2, (Color.blue(color) + 25) / 2);
  }

  static void write(SharedPreferences.Editor editor, int mode, int scale, int color) {
    editor
        .putInt(scaleKey(mode), Math.max(80, Math.min(160, scale)))
        .putInt(colorKey(mode), color | 0xff000000);
  }
}
