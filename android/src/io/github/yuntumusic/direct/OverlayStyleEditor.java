package io.github.yuntumusic.direct;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.Locale;

/** Dialog drafts stay local until the enclosing settings dialog is saved. */
final class OverlayStyleEditor {
  final Activity activity;
  final int mode;
  int scale, color;
  Integer nextColor;
  private Button entry;

  OverlayStyleEditor(Activity activity, int mode) {
    this.activity = activity;
    this.mode = mode;
    scale = OverlayAppearance.scale(activity, mode);
    color = OverlayAppearance.color(activity, mode);
    if (mode != 0 && Api.prefs(activity).contains(OverlayAppearance.nextColorKey(mode)))
      nextColor = OverlayAppearance.nextColor(activity, mode);
  }

  int dp(int n) {
    return Math.round(n * activity.getResources().getDisplayMetrics().density);
  }

  String hex(int value) {
    return String.format(Locale.US, "#%06X", value & 0xffffff);
  }

  GradientDrawable background(int color) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(10));
    return d;
  }

  TextView text(String value, int size, int color) {
    TextView t = new TextView(activity);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    return t;
  }

  Button button(String value) {
    Button b = new Button(activity);
    b.setText(value);
    b.setTextSize(14);
    b.setTextColor(PlayerViews.INK);
    b.setBackground(background(PlayerViews.LINE));
    b.setPadding(dp(10), 0, dp(10), 0);
    return b;
  }

  void addTo(LinearLayout form) {
    entry = button("");
    entry.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
    entry.setContentDescription(OverlayAppearance.NAMES[mode] + "文字样式");
    refresh();
    entry.setOnClickListener(
        new View.OnClickListener() {
          public void onClick(View v) {
            show();
          }
        });
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52));
    lp.topMargin = dp(8);
    form.addView(entry, lp);
  }

  private void refresh() {
    entry.setText(OverlayAppearance.NAMES[mode] + "文字 · " + scale + "% · " + hex(color));
  }

  void write(SharedPreferences.Editor e) {
    OverlayAppearance.write(e, mode, scale, color);
    if (mode != 0) {
      if (nextColor == null) e.remove(OverlayAppearance.nextColorKey(mode));
      else e.putInt(OverlayAppearance.nextColorKey(mode), nextColor);
    }
  }

  private AlertDialog show() {
    final LinearLayout form = new LinearLayout(activity);
    form.setOrientation(LinearLayout.VERTICAL);
    form.setPadding(dp(20), dp(10), dp(20), dp(10));
    form.setBackground(background(PlayerViews.PANEL));
    TextView heading = text(OverlayAppearance.NAMES[mode] + "文字", 20, PlayerViews.INK);
    heading.setTypeface(null, Typeface.BOLD);
    heading.setPadding(0, dp(4), 0, dp(14));
    form.addView(heading);
    final TextView label = text("", 14, PlayerViews.INK);
    form.addView(label);
    final SeekBar size = (SeekBar) activity.getLayoutInflater().inflate(R.layout.seek, null);
    size.setMax(8);
    size.setProgress((scale - 80) / 10);
    size.setContentDescription("悬浮文字字号比例");
    GradientDrawable thumb = background(PlayerViews.RED);
    thumb.setShape(GradientDrawable.OVAL);
    thumb.setSize(dp(20), dp(20));
    size.setThumb(thumb);
    size.setThumbOffset(dp(10));
    ClipDrawable fill =
        new ClipDrawable(background(PlayerViews.RED), Gravity.LEFT, ClipDrawable.HORIZONTAL);
    LayerDrawable track = new LayerDrawable(new Drawable[] {background(PlayerViews.LINE), fill});
    track.setId(0, android.R.id.background);
    track.setId(1, android.R.id.progress);
    size.setProgressDrawable(track);
    form.addView(size, new LinearLayout.LayoutParams(-1, dp(48)));
    final LinearLayout preview = new LinearLayout(activity);
    preview.setOrientation(LinearLayout.VERTICAL);
    preview.setPadding(dp(14), dp(10), dp(14), dp(10));
    preview.setBackground(background(PlayerViews.PANEL));
    final TextView current = text("让喜欢的旋律陪在身边", 20, color);
    current.setTypeface(null, Typeface.BOLD);
    current.setSingleLine(true);
    current.setEllipsize(TextUtils.TruncateAt.END);
    current.setContentDescription("悬浮文字主色预览");
    final TextView following = text("每一段路都有新的风景", 16, OverlayAppearance.secondary(color));
    following.setSingleLine(true);
    following.setEllipsize(TextUtils.TruncateAt.END);
    following.setContentDescription("悬浮文字辅助色预览");
    preview.addView(current);
    preview.addView(following);
    form.addView(preview, new LinearLayout.LayoutParams(-1, -2));
    TextView hint =
        text(
            mode == 2
                ? "当前歌词颜色"
                : mode == 1 ? "当前句和歌名使用主色，上一句和辅助文字自动调暗" : "文字颜色 · 当前句和歌名使用主色，其他文字自动调暗",
            12,
            PlayerViews.MUTED);
    hint.setPadding(0, dp(12), 0, dp(6));
    form.addView(hint);
    final EditText custom = colorControl(form, "自定义悬浮文字颜色", hex(color));
    final EditText upcoming;
    if (mode != 0) {
      form.addView(text("下一句歌词颜色 · 留空时随当前颜色自动调暗", 12, PlayerViews.MUTED));
      upcoming = colorControl(form, "下一句歌词颜色", nextColor == null ? "" : hex(nextColor));
      upcoming.setHint("留空自动，或 #RRGGBB");
    } else upcoming = null;
    form.addView(text("字号 80%–160%；屏幕空间不足时自动适配字号。卡片背景透明度独立，悬浮歌词背景固定全透明。", 12, PlayerViews.MUTED));
    final Runnable render =
        new Runnable() {
          public void run() {
            int percent = 80 + size.getProgress() * 10;
            label.setText("字号比例 · " + percent + "%" + (percent == 100 ? "（默认）" : ""));
            float factor = percent / 100f;
            current.setTextSize((mode == 0 ? 15 : mode == 1 ? 20 : 18) * factor);
            following.setTextSize((mode == 0 ? 12 : 18) * factor);
            Integer selected = parseColor(custom.getText().toString());
            if (selected != null) {
              current.setTextColor(selected);
              Integer pending = upcoming == null ? null : parseColor(upcoming.getText().toString());
              following.setTextColor(
                  pending == null ? OverlayAppearance.secondary(selected) : pending);
              if (upcoming != null
                  && (pending != null || upcoming.getText().toString().trim().length() == 0))
                upcoming.setError(null);
              custom.setError(null);
            }
          }
        };
    size.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar b, int p, boolean user) {
            render.run();
          }

          public void onStartTrackingTouch(SeekBar b) {}

          public void onStopTrackingTouch(SeekBar b) {}
        });
    TextWatcher watcher =
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int st, int c, int a) {}

          public void onTextChanged(CharSequence s, int st, int before, int count) {
            render.run();
          }

          public void afterTextChanged(Editable e) {}
        };
    custom.addTextChangedListener(watcher);
    if (upcoming != null) upcoming.addTextChangedListener(watcher);
    render.run();
    ScrollView scroll = new ScrollView(activity);
    scroll.addView(form);
    final AlertDialog dialog =
        new AlertDialog.Builder(activity)
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setNeutralButton("恢复默认", null)
            .setPositiveButton("应用到设置", null)
            .create();
    dialog.show();
    dialog.getWindow().setBackgroundDrawable(background(PlayerViews.PANEL));
    dialog
        .getWindow()
        .setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    for (int which :
        new int[] {
          AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_POSITIVE
        }) dialog.getButton(which).setTextColor(PlayerViews.RED);
    dialog
        .getButton(AlertDialog.BUTTON_NEUTRAL)
        .setOnClickListener(
            new View.OnClickListener() {
              public void onClick(View v) {
                size.setProgress(2);
                custom.setText(hex(PlayerViews.INK));
                if (upcoming != null) upcoming.setText("");
              }
            });
    dialog
        .getButton(AlertDialog.BUTTON_POSITIVE)
        .setOnClickListener(
            new View.OnClickListener() {
              public void onClick(View v) {
                Integer selected = parseColor(custom.getText().toString());
                if (selected == null) {
                  custom.setError("请输入六位颜色值，如 #FFD166");
                  return;
                }
                Integer pending =
                    upcoming == null ? null : parseColor(upcoming.getText().toString());
                if (upcoming != null
                    && upcoming.getText().toString().trim().length() > 0
                    && pending == null) {
                  upcoming.setError("请输入六位颜色值，或留空使用自动颜色");
                  return;
                }
                nextColor = pending;
                scale = 80 + size.getProgress() * 10;
                color = selected;
                refresh();
                dialog.dismiss();
              }
            });
    return dialog;
  }

  private EditText colorControl(LinearLayout form, String description, String value) {
    final EditText custom = new EditText(activity);
    custom.setSingleLine(true);
    custom.setTextColor(PlayerViews.INK);
    custom.setTextSize(16);
    custom.setHintTextColor(PlayerViews.MUTED);
    custom.setHint("#RRGGBB，例如 #FFD166");
    custom.setContentDescription(description);
    custom.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
    GradientDrawable input = background(PlayerViews.BG);
    input.setStroke(dp(1), PlayerViews.LINE);
    custom.setBackground(input);
    custom.setPadding(dp(10), 0, dp(10), 0);
    custom.setText(value);
    final int[] colors = {
      PlayerViews.INK, 0xffffd166, PlayerViews.RED, 0xff70d6b1, 0xff80bfff, 0xffc4a1ff
    };
    String[] names = {"默认白", "暖黄", "云途红", "薄荷绿", "天蓝", "浅紫"};
    for (int row = 0; row < 2; row++) {
      LinearLayout palette = new LinearLayout(activity);
      for (int col = 0; col < 3; col++) {
        final int index = row * 3 + col;
        Button b = button("● " + names[index]);
        b.setTextColor(colors[index]);
        b.setContentDescription(description + "：" + names[index]);
        b.setOnClickListener(
            new View.OnClickListener() {
              public void onClick(View v) {
                custom.setText(hex(colors[index]));
              }
            });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        palette.addView(b, lp);
      }
      form.addView(palette);
    }
    form.addView(custom, new LinearLayout.LayoutParams(-1, dp(48)));
    return custom;
  }

  static Integer parseColor(String value) {
    String s = value.trim();
    if (!s.matches("#?[0-9a-fA-F]{6}")) return null;
    return Color.parseColor(s.startsWith("#") ? s : "#" + s);
  }
}
