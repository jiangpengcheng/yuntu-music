package io.github.yuntumusic.direct;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.util.Locale;
import org.json.*;

/** App-owned overlay: no ECARX classes, accessibility service, or system UID needed. */
final class FloatingPlayer {
  private final PlaybackService service;
  private final WindowManager windows;
  private LinearLayout card;
  private GradientDrawable background;
  private int appliedTransparency = -1;
  private int textScale = 100, textColor = PlayerViews.INK;
  private TextView title, artist, time, lyric;
  private TextView nextLyric;
  private boolean lyricsOnly, largeCard;
  private TextView[] largeLines;
  private View.OnTouchListener lyricTouch;
  private String lyricSong = "", lyricMessage = "暂无歌词";
  private JSONArray lyricLines;
  private int lyricVersion;
  private boolean lyricFailed, destroyed;
  private ProgressBar progress;
  private PlayerViews.Icon play;
  private PlayerViews.Art cover;
  private WindowManager.LayoutParams params;
  private String artwork = "", error = "";
  private boolean blocked;
  private boolean compatWindow, screenSuppressed;
  private int screenWidth, screenHeight;
  private final BroadcastReceiver screen =
      new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
          screenSuppressed = Intent.ACTION_SCREEN_OFF.equals(i.getAction());
          if (screenSuppressed) hide();
          service.updateFloatingPlayer();
        }
      };

  FloatingPlayer(PlaybackService service) {
    this.service = service;
    windows = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
    IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
    filter.addAction(Intent.ACTION_USER_PRESENT);
    filter.addAction(Intent.ACTION_SCREEN_ON);
    service.registerReceiver(screen, filter);
  }

  static boolean allowed(Context context) {
    if (Build.VERSION.SDK_INT < 23) return true; // addView still checks the OEM/AppOps policy.
    try {
      return (Boolean)
          Settings.class.getMethod("canDrawOverlays", Context.class).invoke(null, context);
    } catch (Exception e) {
      return false;
    }
  }

  static void openPermission(Activity activity) {
    Intent intent;
    if (Build.VERSION.SDK_INT >= 23) {
      intent =
          new Intent(
              "android.settings.action.MANAGE_OVERLAY_PERMISSION",
              Uri.parse("package:" + activity.getPackageName()));
    } else {
      intent =
          new Intent(
              Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
              Uri.parse("package:" + activity.getPackageName()));
    }
    try {
      activity.startActivity(intent);
    } catch (ActivityNotFoundException e) {
      Toast.makeText(activity, "请在系统应用权限中允许云途显示悬浮窗", Toast.LENGTH_LONG).show();
    }
  }

  void retry() {
    blocked = false;
    error = "";
  }

  String error() {
    return error;
  }

  static boolean compatibility(Context context) {
    return Api.prefs(context).getBoolean("overlayCarCompat", Build.VERSION.SDK_INT == 19);
  }

  static boolean isCs11(int sdk, String manufacturer, String model) {
    return sdk == 19
        && "Freescale".equalsIgnoreCase(manufacturer)
        && "CS11".equalsIgnoreCase(model);
  }

  static boolean cs11Profile() {
    return isCs11(Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL);
  }

  static boolean showOverKeyguard(boolean secure, boolean compat, boolean cs11) {
    // CS11 reports both lock flags while the user is operating its desktop (0.5.4 field log).
    // This only permits our overlay; it does not dismiss or disable the system keyguard.
    return compat && (cs11 || !secure);
  }

  static boolean lockBlocks(boolean locked, boolean secure, boolean compat, boolean cs11) {
    return locked && !showOverKeyguard(secure, compat, cs11);
  }

  void update(boolean uiVisible, boolean stopped) {
    PowerManager power = (PowerManager) service.getSystemService(Context.POWER_SERVICE);
    KeyguardManager keyguard = (KeyguardManager) service.getSystemService(Context.KEYGUARD_SERVICE);
    boolean full = Api.prefs(service).getBoolean("floating", false);
    boolean compactEnabled = Api.prefs(service).getBoolean("floatingLyrics", false);
    boolean on = power.isScreenOn() && !screenSuppressed, locked = keyguard.isKeyguardLocked();
    boolean secure = keyguard.isKeyguardSecure(), compat = compatibility(service);
    boolean cs11 = cs11Profile();
    boolean permission = allowed(service), hasTrack = service.current() != null;
    if (destroyed
        || uiVisible
        || stopped
        || (!full && !compactEnabled)
        || !hasTrack
        || !on
        || lockBlocks(locked, secure, compat, cs11)
        || !permission
        || blocked) {
      hide();
      return;
    }
    boolean showOverKeyguard = showOverKeyguard(secure, compat, cs11);
    if (card != null && compatWindow != showOverKeyguard) hide();
    compatWindow = showOverKeyguard;
    try {
      boolean compact = Api.prefs(service).getBoolean("floatingLyrics", false);
      boolean large = !compact && Api.prefs(service).getBoolean("floatingLarge", false);
      if (card != null && (lyricsOnly != compact || largeCard != large)) hide();
      int mode = compact ? 2 : large ? 1 : 0;
      int scale = OverlayAppearance.scale(service, mode);
      int color = OverlayAppearance.color(service, mode);
      if (card != null && (textScale != scale || textColor != color)) hide();
      textScale = scale;
      textColor = color;
      lyricsOnly = compact;
      largeCard = large;
      if (card == null) show();
      updateBackground();
      fitScreen();
      Track track = service.current();
      if (!lyricsOnly) {
        title.setText(track.name);
        artist.setText(track.artist);
        time.setText(clock(service.position()) + " / " + clock(service.duration()));
        progress.setMax(Math.max(1, service.duration()));
        progress.setProgress(service.position());
        play.playing(service.isPlaying());
      }
      loadLyrics(track);
      renderLyric();
      if (!lyricsOnly && !artwork.equals(track.cover)) {
        artwork = track.cover;
        ArtworkLoader.load(cover, artwork, 160);
      }
    } catch (RuntimeException e) {
      hide();
      blocked = true;
      error = "悬浮窗创建失败（" + e.getClass().getSimpleName() + "），请检查悬浮窗权限后重新开启";
      Toast.makeText(service, error, Toast.LENGTH_LONG).show();
    }
  }

  private void loadLyrics(final Track track) {
    if (service.isBluetooth()) {
      if (!track.id.equals(lyricSong)) {
        lyricVersion++;
        lyricSong = track.id;
      }
      JSONObject data = service.bluetoothPresentation();
      lyricLines = data.optJSONArray("lines");
      lyricMessage = data.optString("plain");
      if (lyricMessage.length() == 0) lyricMessage = data.optString("message");
      lyricFailed = false;
      return;
    }
    if (track.id.equals(lyricSong)) return;
    lyricSong = track.id;
    lyricLines = null;
    lyricMessage = "正在加载歌词…";
    lyricFailed = false;
    final int ticket = ++lyricVersion;
    Api.request(
        service,
        "GET",
        "/v1/songs/" + track.id + "/presentation",
        new Api.Callback() {
          public void done(JSONObject data, String error) {
            if (destroyed || ticket != lyricVersion) return;
            if (service.current() == null || !track.id.equals(service.current().id)) {
              // A hidden card does not fetch new songs. Do not leave its abandoned request cached.
              lyricSong = "";
              return;
            }
            lyricFailed = error != null || data.optBoolean("lyricError");
            lyricLines = error == null ? data.optJSONArray("lines") : null;
            lyricMessage =
                lyricFailed ? "歌词暂时不可用" : data.optBoolean("instrumental") ? "纯音乐，请欣赏" : "暂无歌词";
            if (lyricFailed && !lyricsOnly) lyricMessage += "，点击重试";
            if (!lyricFailed && (lyricLines == null || lyricLines.length() == 0)) {
              String plain = data.optString("plain").trim();
              if (plain.length() > 0) lyricMessage = "歌词无时间信息，点击查看";
            }
            renderLyric();
          }
        });
  }

  private void renderLyric() {
    if (card == null || lyric == null) return;
    String line = lyricsOnly && lyricFailed ? "歌词暂时不可用" : lyricMessage;
    int currentLine = -1;
    if (lyricLines != null && lyricLines.length() > 0) {
      line = "前奏…";
      int position = service.position();
      for (int i = 0; i < lyricLines.length(); i++) {
        JSONObject item = lyricLines.optJSONObject(i);
        if (item == null) continue;
        if (item.optInt("time") > position) break;
        String text = item.optString("text").trim();
        line = text.length() > 0 ? text : "间奏…";
        currentLine = i;
      }
    }
    if (largeLines != null) {
      for (int row = 0; row < largeLines.length; row++) {
        String value = "";
        int index = currentLine + row - largeLines.length / 2;
        if (row == largeLines.length / 2) value = line;
        else if (lyricLines != null && index >= 0 && index < lyricLines.length()) {
          JSONObject item = lyricLines.optJSONObject(index);
          if (item != null) value = item.optString("text").trim();
        }
        if (!value.contentEquals(largeLines[row].getText())) largeLines[row].setText(value);
        if (row > largeLines.length / 2) {
          int upcomingColor = OverlayAppearance.nextColor(service, 1);
          if (largeLines[row].getCurrentTextColor() != upcomingColor)
            largeLines[row].setTextColor(upcomingColor);
        }
      }
    }
    if (lyricsOnly) {
      // Count actual lyric sentences so blank timestamp markers do not swap the two rows.
      String current = "", next = "";
      int sentence = -1;
      if (lyricLines != null) {
        for (int i = 0; i < lyricLines.length(); i++) {
          JSONObject item = lyricLines.optJSONObject(i);
          if (item == null) continue;
          String value = item.optString("text").trim();
          if (value.length() == 0) continue;
          if (i <= currentLine) {
            sentence++;
            if (i == currentLine) current = value;
          } else {
            next = value;
            break;
          }
        }
      }
      if (service.isBluetooth() && (lyricLines == null || lyricLines.length() == 0)) {
        current = service.bluetoothPresentation().optString("plain");
      }
      boolean firstCurrent = sentence < 0 || sentence % 2 == 0;
      setLyricRow(lyric, firstCurrent ? current : next, firstCurrent);
      setLyricRow(nextLyric, firstCurrent ? next : current, !firstCurrent);
      layoutLyrics(true);
    } else if (!line.contentEquals(lyric.getText())) lyric.setText(line);
  }

  private void setLyricRow(TextView row, String value, boolean current) {
    if (row == null) return;
    if (!value.contentEquals(row.getText())) row.setText(value);
    int color = current ? textColor : OverlayAppearance.nextColor(service);
    if (row.getCurrentTextColor() != color) row.setTextColor(color);
    if (row.getTypeface() == null || row.getTypeface().isBold() != current)
      row.setTypeface(current ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
  }

  private int dp(int n) {
    return (int) (n * service.getResources().getDisplayMetrics().density + .5f);
  }

  private String clock(int ms) {
    int sec = Math.max(0, ms) / 1000;
    return String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
  }

  private TextView text(int size, int color) {
    TextView v = new TextView(service);
    v.setTextSize(size * textScale / 100f);
    v.setTextColor(color == PlayerViews.INK ? textColor : OverlayAppearance.secondary(textColor));
    v.setSingleLine(true);
    v.setEllipsize(TextUtils.TruncateAt.END);
    return v;
  }

  private int textHeight(TextView view) {
    Paint.FontMetricsInt font = view.getPaint().getFontMetricsInt();
    return font.bottom - font.top + dp(4);
  }

  private void openApp() {
    service.startActivity(
        new Intent(service, MainActivity.class)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP));
  }

  private PlayerViews.Icon icon(String kind, String label, Runnable action) {
    return new PlayerViews.Icon(service, kind, label, action);
  }

  private void show() {
    if (lyricsOnly) {
      showLyrics();
      attachWindow();
      return;
    }
    card = new LinearLayout(service);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(dp(12), dp(10), dp(12), dp(10));
    updateBackground();
    measureScreen();
    card.setContentDescription(largeCard ? "云途大号悬浮卡片" : "云途悬浮播放器");
    LinearLayout header = new LinearLayout(service);
    header.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout drag = new LinearLayout(service);
    drag.setGravity(Gravity.CENTER_VERTICAL);
    cover = new PlayerViews.Art(service, false);
    drag.addView(
        cover, new LinearLayout.LayoutParams(dp(largeCard ? 60 : 48), dp(largeCard ? 60 : 48)));
    LinearLayout names = new LinearLayout(service);
    names.setOrientation(LinearLayout.VERTICAL);
    names.setPadding(dp(10), 0, dp(4), 0);
    title = text(largeCard ? 20 : 17, PlayerViews.INK);
    title.setTypeface(null, Typeface.BOLD);
    artist = text(largeCard ? 14 : 12, PlayerViews.MUTED);
    names.addView(title);
    names.addView(artist);
    drag.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
    drag.setContentDescription("拖动卡片，点击打开云途");
    drag.setOnClickListener(
        new View.OnClickListener() {
          public void onClick(View v) {
            openApp();
          }
        });
    drag.setOnTouchListener(
        new View.OnTouchListener() {
          float downX, downY;
          int startX, startY;
          boolean moved;

          public boolean onTouch(View v, MotionEvent event) {
            if (params == null) return false;
            switch (event.getActionMasked()) {
              case MotionEvent.ACTION_DOWN:
                downX = event.getRawX();
                downY = event.getRawY();
                startX = params.x;
                startY = params.y;
                moved = false;
                return true;
              case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                if (Math.abs(dx) + Math.abs(dy) > dp(6)) moved = true;
                if (moved) {
                  params.x = startX + (int) dx;
                  params.y = startY + (int) dy;
                  clamp();
                  move();
                }
                return true;
              case MotionEvent.ACTION_UP:
                if (!moved) v.performClick();
                else savePosition();
                return true;
              case MotionEvent.ACTION_CANCEL:
                savePosition();
                return true;
            }
            return false;
          }
        });
    int headerHeight = Math.max(dp(largeCard ? 64 : 50), textHeight(title) + textHeight(artist));
    header.addView(drag, new LinearLayout.LayoutParams(0, headerHeight, 1));
    PlayerViews.Icon close =
        icon(
            "close",
            "关闭悬浮卡片",
            new Runnable() {
              public void run() {
                Api.prefs(service).edit().putBoolean("floating", false).apply();
                hide();
              }
            });
    close.bare = true;
    header.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
    card.addView(header);
    lyric = text(15, PlayerViews.INK);
    lyric.setGravity(Gravity.CENTER_VERTICAL);
    lyric.setContentDescription("当前歌词，点击查看或重试");
    lyric.setOnClickListener(
        new View.OnClickListener() {
          public void onClick(View v) {
            if (lyricFailed) {
              lyricSong = "";
              service.updateFloatingPlayer();
            } else openApp();
          }
        });
    if (largeCard) {
      int available = Math.max(dp(54), screenHeight - dp(114) - headerHeight);
      int desiredRow = Math.round(dp(32) * textScale / 100f);
      largeLines = new TextView[3];
      int rowHeight = Math.max(dp(18), Math.min(desiredRow, available / largeLines.length));
      float font =
          Math.min(
              20 * textScale / 100f,
              rowHeight / service.getResources().getDisplayMetrics().scaledDensity * .65f);
      for (int i = 0; i < largeLines.length; i++) {
        TextView row = i == largeLines.length / 2 ? lyric : text(18, PlayerViews.MUTED);
        row.setGravity(Gravity.CENTER);
        row.setTextSize(
            i == largeLines.length / 2 ? font : Math.max(font * .75f, font - 2 * textScale / 100f));
        if (i == largeLines.length / 2) {
          row.setTextSize(font);
          row.setTypeface(null, Typeface.BOLD);
        } else {
          row.setOnClickListener(
              new View.OnClickListener() {
                public void onClick(View v) {
                  openApp();
                }
              });
        }
        largeLines[i] = row;
        card.addView(row, new LinearLayout.LayoutParams(-1, rowHeight));
      }
    } else
      card.addView(lyric, new LinearLayout.LayoutParams(-1, Math.max(dp(32), textHeight(lyric))));
    progress = new ProgressBar(service, null, android.R.attr.progressBarStyleHorizontal);
    progress.setProgressDrawable(progressDrawable());
    LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, dp(4));
    pp.topMargin = dp(10);
    pp.bottomMargin = dp(8);
    card.addView(progress, pp);
    LinearLayout controls = new LinearLayout(service);
    controls.setGravity(Gravity.CENTER_VERTICAL);
    time = text(13, PlayerViews.MUTED);
    controls.addView(time, new LinearLayout.LayoutParams(0, Math.max(dp(24), textHeight(time)), 1));
    PlayerViews.Icon prev =
        icon(
            "previous",
            "上一首",
            new Runnable() {
              public void run() {
                service.previous();
              }
            });
    play =
        icon(
            "play",
            "播放",
            new Runnable() {
              public void run() {
                service.toggle();
              }
            });
    play.active = true;
    PlayerViews.Icon next =
        icon(
            "next",
            "下一首",
            new Runnable() {
              public void run() {
                service.next(true);
              }
            });
    for (View v : new View[] {prev, play, next}) {
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
      lp.leftMargin = dp(6);
      controls.addView(v, lp);
    }
    card.addView(controls);
    attachWindow();
  }

  static String transparencyKey(boolean lyrics) {
    return lyrics ? "lyricFloatTransparency" : "floatTransparency";
  }

  static int transparency(Context context, boolean lyrics) {
    if (lyrics) return 100;
    return Math.max(0, Math.min(100, Api.prefs(context).getInt(transparencyKey(false), 4)));
  }

  private void updateBackground() {
    int value = transparency(service, lyricsOnly);
    if (background == null) {
      background = new GradientDrawable();
      background.setCornerRadius(dp(16));
      card.setBackground(background);
      appliedTransparency = -1;
    }
    if (value == appliedTransparency) return;
    int alpha = Math.round(255 * (100 - value) / 100f);
    background.setColor((alpha << 24) | 0x141419);
    background.setStroke(dp(1), (alpha << 24) | 0x34343d);
    appliedTransparency = value;
  }

  private void showLyrics() {
    card = new LinearLayout(service);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(dp(16), dp(4), dp(16), dp(4));
    updateBackground();
    card.setContentDescription("云途悬浮歌词，单击打开应用，按住上下拖动");
    lyric = text(18, PlayerViews.INK);
    nextLyric = text(18, PlayerViews.MUTED);
    for (TextView row : new TextView[] {lyric, nextLyric}) {
      // Equal row sizes prevent layout jumps as current/next exchange roles.
      row.setTypeface(null, Typeface.BOLD);
      row.setGravity(Gravity.CENTER);
      row.setShadowLayer(dp(2), 0, dp(1), 0xcc000000);
      card.addView(row, new LinearLayout.LayoutParams(-1, Math.max(dp(28), textHeight(row))));
    }
    card.setOnClickListener(
        new View.OnClickListener() {
          public void onClick(View v) {
            openApp();
          }
        });
    lyricTouch =
        new View.OnTouchListener() {
          float downX, downY;
          int startY;
          boolean touching, moved, cancelled;

          public boolean onTouch(View v, MotionEvent event) {
            if (params == null) return false;
            switch (event.getActionMasked()) {
              case MotionEvent.ACTION_DOWN:
                downX = event.getRawX();
                downY = event.getRawY();
                startY = params.y;
                touching = true;
                moved = cancelled = false;
                return true;
              case MotionEvent.ACTION_MOVE:
                if (!touching || cancelled) return true;
                float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                if (Math.abs(dx) + Math.abs(dy) > dp(6)) moved = true;
                if (moved) {
                  params.y = startY + (int) dy;
                  clamp();
                  move();
                }
                return true;
              case MotionEvent.ACTION_POINTER_DOWN:
              case MotionEvent.ACTION_CANCEL:
                cancelled = true;
                touching = false;
                if (moved) savePosition();
                return true;
              case MotionEvent.ACTION_UP:
                boolean click = touching && !moved && !cancelled;
                touching = false;
                if (moved) savePosition();
                if (click) v.performClick();
                return true;
            }
            return true;
          }
        };
    card.setOnTouchListener(lyricTouch);
  }

  private int overlayWidth() {
    if (!lyricsOnly) return dp(340);
    if (!hasVisibleLyrics()) return 1;
    float width =
        Math.max(
            lyric.getPaint().measureText(lyric.getText().toString()),
            nextLyric.getPaint().measureText(nextLyric.getText().toString()));
    return Math.min(
        Math.max(1, screenWidth - dp(16)),
        (int) Math.ceil(width) + card.getPaddingLeft() + card.getPaddingRight() + dp(4));
  }

  private boolean hasVisibleLyrics() {
    return lyric != null && nextLyric != null && (lyric.length() > 0 || nextLyric.length() > 0);
  }

  private void layoutLyrics(boolean attached) {
    if (!lyricsOnly || params == null) return;
    int width = overlayWidth();
    int x = Math.max(0, (screenWidth - width) / 2);
    boolean visible = hasVisibleLyrics();
    int height = visible ? WindowManager.LayoutParams.WRAP_CONTENT : 1;
    int flags =
        visible
            ? params.flags & ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            : params.flags | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
    if (params.width == width && params.x == x && params.height == height && params.flags == flags)
      return;
    params.width = width;
    params.x = x;
    params.height = height;
    params.flags = flags;
    clamp();
    if (attached) move();
  }

  private String positionKey(String axis) {
    return (lyricsOnly ? "lyricFloat" : largeCard ? "largeFloat" : "float") + axis;
  }

  private void attachWindow() {
    // 2038 = TYPE_APPLICATION_OVERLAY (API 26); numeric value keeps strict API 19 compilation.
    params =
        new WindowManager.LayoutParams(
            overlayWidth(),
            -2,
            Build.VERSION.SDK_INT >= 26 ? 2038 : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | (compatWindow ? WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED : 0),
            PixelFormat.TRANSLUCENT);
    params.gravity = Gravity.TOP | Gravity.LEFT;
    params.setTitle(lyricsOnly ? "云途悬浮歌词" : largeCard ? "云途大号悬浮卡片" : "云途悬浮播放器");
    measureScreen();
    params.width = Math.min(overlayWidth(), screenWidth);
    params.x =
        Api.prefs(service)
            .getInt(
                positionKey("X"),
                lyricsOnly
                    ? Math.max(0, (screenWidth - params.width) / 2)
                    : Math.max(0, screenWidth - params.width - dp(16)));
    params.y =
        Api.prefs(service).getInt(positionKey("Y"), lyricsOnly ? screenHeight - dp(150) : dp(80));
    layoutLyrics(false);
    clamp();
    windows.addView(card, params);
    artwork = "\u0000";
  }

  private android.graphics.drawable.Drawable progressDrawable() {
    android.graphics.drawable.ClipDrawable fill =
        new android.graphics.drawable.ClipDrawable(
            new android.graphics.drawable.ColorDrawable(PlayerViews.RED),
            Gravity.LEFT,
            android.graphics.drawable.ClipDrawable.HORIZONTAL);
    android.graphics.drawable.LayerDrawable layers =
        new android.graphics.drawable.LayerDrawable(
            new android.graphics.drawable.Drawable[] {
              new android.graphics.drawable.ColorDrawable(PlayerViews.LINE), fill
            });
    layers.setId(0, android.R.id.background);
    layers.setId(1, android.R.id.progress);
    return layers;
  }

  private void measureScreen() {
    Point p = new Point();
    windows.getDefaultDisplay().getSize(p);
    screenWidth = p.x;
    screenHeight = p.y;
  }

  private void fitScreen() {
    int w = screenWidth, h = screenHeight;
    measureScreen();
    if (w != screenWidth || h != screenHeight) {
      hide();
      show();
    }
  }

  private void clamp() {
    params.x = Math.max(0, Math.min(params.x, screenWidth - params.width));
    int height = 0;
    if (card != null) {
      card.measure(
          View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
      height = card.getMeasuredHeight();
    }
    params.y = Math.max(0, Math.min(params.y, screenHeight - height - dp(28)));
  }

  private void move() {
    try {
      windows.updateViewLayout(card, params);
    } catch (RuntimeException e) {
      hide();
      blocked = true;
      error = "悬浮窗移动失败，请重新开启悬浮卡片";
    }
  }

  private void savePosition() {
    if (params != null)
      Api.prefs(service)
          .edit()
          .putInt(positionKey("X"), params.x)
          .putInt(positionKey("Y"), params.y)
          .apply();
  }

  private void hide() {
    if (card != null) {
      try {
        windows.removeView(card);
      } catch (RuntimeException e) {
      }
      card = null;
    }
    background = null;
    appliedTransparency = -1;
    params = null;
    artwork = "";
    title = artist = time = lyric = nextLyric = null;
    cover = null;
    largeLines = null;
    progress = null;
    play = null;
    lyricTouch = null;
  }

  void destroy() {
    destroyed = true;
    lyricVersion++;
    hide();
    service.unregisterReceiver(screen);
  }
}
