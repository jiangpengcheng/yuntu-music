package io.github.yuntumusic.direct;

import android.content.Context;
import android.graphics.*;
import android.text.*;
import android.view.*;
import java.util.*;
import org.json.*;

/** Small, density-aware native views; all APIs are available on Android 4.4. */
final class PlayerViews {
  static final int BG = 0xff0d0d11,
      PANEL = 0xff141419,
      INK = 0xfff5f5f7,
      MUTED = 0xff878790,
      RED = 0xffed4149,
      LINE = 0xff27272e;

  static float density(Context c) {
    return c.getResources().getDisplayMetrics().density;
  }

  static final class Icon extends View {
    final Paint p = new Paint(3);
    String kind;
    boolean active = false, bare = false, tinted = false;

    Icon(Context c, String kind, String label, final Runnable click) {
      super(c);
      this.kind = kind;
      setContentDescription(label);
      setFocusable(true);
      setClickable(true);
      setOnClickListener(
          new OnClickListener() {
            public void onClick(View v) {
              click.run();
            }
          });
    }

    void playing(boolean playing) {
      kind = playing ? "pause" : "play";
      setContentDescription(playing ? "暂停" : "播放");
      invalidate();
    }

    public void setPressed(boolean pressed) {
      super.setPressed(pressed);
      invalidate();
    }

    protected void onDraw(Canvas canvas) {
      float s = Math.min(getWidth(), getHeight()), cx = getWidth() / 2f, cy = getHeight() / 2f;
      p.setStyle(Paint.Style.FILL);
      p.setColor(isPressed() ? 0xff503039 : active ? 0xff37262e : 0xff25252d);
      if (!bare || isPressed()) canvas.drawCircle(cx, cy, s * .49f, p);
      canvas.save();
      canvas.translate(cx - s / 2, cy - s / 2);
      canvas.scale(s / 100, s / 100);
      // Match the queue glyph's optical size while preserving a 44 dp touch target.
      float glyphScale =
          kind.equals("heart") || kind.equals("save")
              ? .685f
              : kind.equals("shuffle")
                  ? .66f
                  : kind.equals("repeat") || kind.equals("order") ? .60f : 1f;
      canvas.scale(glyphScale, glyphScale, 50, 50);
      p.setColor(tinted ? RED : INK);
      p.setStrokeWidth(4);
      p.setStrokeCap(Paint.Cap.ROUND);
      p.setStrokeJoin(Paint.Join.ROUND);
      if (kind.equals("pause")) {
        canvas.drawRect(36, 34, 45, 66, p);
        canvas.drawRect(55, 34, 64, 66, p);
      } else if (kind.equals("play")) {
        triangle(canvas, 42, 32, 42, 68, 68, 50);
      } else if (kind.equals("previous")) {
        canvas.drawRect(34, 35, 39, 65, p);
        triangle(canvas, 63, 35, 63, 65, 41, 50);
      } else if (kind.equals("next")) {
        canvas.drawRect(61, 35, 66, 65, p);
        triangle(canvas, 37, 35, 37, 65, 59, 50);
      } else if (kind.equals("heart")) {
        Path heart = new Path();
        heart.moveTo(50, 72);
        heart.cubicTo(38, 62, 22, 50, 25, 37);
        heart.cubicTo(28, 24, 43, 24, 50, 35);
        heart.cubicTo(57, 24, 72, 24, 75, 37);
        heart.cubicTo(78, 50, 62, 62, 50, 72);
        p.setStyle(tinted ? Paint.Style.FILL : Paint.Style.STROKE);
        canvas.drawPath(heart, p);
      } else if (kind.equals("save")) {
        p.setStyle(Paint.Style.STROKE);
        Path folder = new Path();
        folder.moveTo(25, 70);
        folder.lineTo(25, 32);
        folder.lineTo(43, 32);
        folder.lineTo(50, 39);
        folder.lineTo(75, 39);
        folder.lineTo(75, 70);
        folder.close();
        canvas.drawPath(folder, p);
        canvas.drawLine(40, 55, 60, 55, p);
        canvas.drawLine(50, 45, 50, 65, p);
      } else if (kind.equals("close")) {
        canvas.drawLine(35, 35, 65, 65, p);
        canvas.drawLine(65, 35, 35, 65, p);
      } else if (kind.equals("shuffle")) {
        p.setStyle(Paint.Style.STROKE);
        Path a = new Path();
        a.moveTo(25, 32);
        a.cubicTo(48, 32, 51, 68, 73, 68);
        canvas.drawPath(a, p);
        Path b = new Path();
        b.moveTo(25, 68);
        b.cubicTo(48, 68, 51, 32, 73, 32);
        canvas.drawPath(b, p);
        canvas.drawLine(65, 24, 74, 32, p);
        canvas.drawLine(65, 40, 74, 32, p);
        canvas.drawLine(65, 60, 74, 68, p);
        canvas.drawLine(65, 76, 74, 68, p);
      } else if (kind.equals("repeat") || kind.equals("order")) {
        p.setStyle(Paint.Style.STROKE);
        Path top = new Path();
        top.moveTo(24, 48);
        top.lineTo(24, 35);
        top.quadTo(24, 29, 31, 29);
        top.lineTo(74, 29);
        canvas.drawPath(top, p);
        canvas.drawLine(65, 21, 74, 29, p);
        canvas.drawLine(65, 37, 74, 29, p);
        Path bottom = new Path();
        bottom.moveTo(76, 52);
        bottom.lineTo(76, 65);
        bottom.quadTo(76, 71, 69, 71);
        bottom.lineTo(26, 71);
        canvas.drawPath(bottom, p);
        canvas.drawLine(35, 63, 26, 71, p);
        canvas.drawLine(35, 79, 26, 71, p);
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(29);
        p.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        p.setTextAlign(Paint.Align.CENTER);
        if (kind.equals("repeat")) canvas.drawText("1", 50, 61, p);
      } else if (kind.equals("queue")) {
        for (int i = 0; i < 3; i++) {
          canvas.drawCircle(33, 37 + i * 13, 2, p);
          canvas.drawRect(41, 35 + i * 13, 68, 39 + i * 13, p);
        }
      }
      canvas.restore();
    }

    private void triangle(Canvas c, float x, float y, float a, float b, float u, float v) {
      Path path = new Path();
      path.moveTo(x, y);
      path.lineTo(a, b);
      path.lineTo(u, v);
      path.close();
      c.drawPath(path, p);
    }

    public CharSequence getAccessibilityClassName() {
      return "android.widget.Button";
    }
  }

  static final class Art extends View {
    final Paint paint = new Paint(3);
    final RectF rect = new RectF();
    Bitmap bitmap;
    BitmapShader shader;
    final boolean logo;

    Art(Context c, boolean logo) {
      super(c);
      this.logo = logo;
      setContentDescription(logo ? "云途音乐" : "专辑封面");
    }

    void bitmap(Bitmap b) {
      bitmap = b;
      shader = b == null ? null : new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
      invalidate();
    }

    protected void onDraw(Canvas c) {
      float w = getWidth(), h = getHeight(), r = Math.min(w, h) * (logo ? .22f : .07f);
      rect.set(0, 0, w, h);
      paint.setShader(null);
      if (bitmap != null) {
        Matrix m = new Matrix();
        float scale = Math.max(w / bitmap.getWidth(), h / bitmap.getHeight());
        m.setScale(scale, scale);
        m.postTranslate((w - bitmap.getWidth() * scale) / 2, (h - bitmap.getHeight() * scale) / 2);
        shader.setLocalMatrix(m);
        paint.setShader(shader);
        c.drawRoundRect(rect, r, r, paint);
        paint.setShader(null);
        return;
      }
      paint.setShader(
          new LinearGradient(
              0,
              0,
              w,
              h,
              new int[] {logo ? 0xffed4149 : 0xff403039, logo ? 0xffb51f32 : 0xff1c1c26},
              null,
              Shader.TileMode.CLAMP));
      c.drawRoundRect(rect, r, r, paint);
      paint.setShader(null);
      float s = Math.min(w, h), cx = w / 2, cy = h / 2;
      if (!logo) {
        paint.setColor(0xff22212a);
        c.drawCircle(cx, cy, s * .34f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(s * .004f);
        paint.setColor(0xff39313b);
        for (int i = 0; i < 5; i++) c.drawCircle(cx, cy, s * (.2f + i * .023f), paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(RED);
        c.drawCircle(cx, cy, s * .095f, paint);
        paint.setColor(0xff17171d);
        c.drawCircle(cx, cy, s * .015f, paint);
      } else {
        paint.setColor(Color.WHITE);
        paint.setStrokeWidth(s * .065f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        c.drawArc(new RectF(w * .23f, h * .23f, w * .77f, h * .77f), -55, 295, false, paint);
        c.drawLine(w * .53f, h * .25f, w * .53f, h * .57f, paint);
        paint.setStyle(Paint.Style.FILL);
        c.drawCircle(w * .46f, h * .59f, s * .07f, paint);
      }
    }
  }

  static final class Progress extends View {
    final Paint p = new Paint(3);
    float value;

    Progress(Context c) {
      super(c);
    }

    void value(float n) {
      value = Math.max(0, Math.min(1, n));
      invalidate();
    }

    protected void onDraw(Canvas c) {
      p.setColor(LINE);
      c.drawRect(0, 0, getWidth(), getHeight(), p);
      p.setColor(RED);
      c.drawRect(0, 0, getWidth() * value, getHeight(), p);
    }
  }

  static final class Lyrics extends View {
    final TextPaint normal = new TextPaint(3), highlight = new TextPaint(3);
    final ArrayList<String> texts = new ArrayList<String>();
    final ArrayList<Integer> times = new ArrayList<Integer>();
    final ArrayList<StaticLayout> layouts = new ArrayList<StaticLayout>();
    int current = -1, display = 0, layoutWidth = -1;
    String message = "选一首歌，让旅途有旋律";
    boolean timed = true;
    float startY;
    int startDisplay;
    long manualUntil = 0;
    final float d;

    Lyrics(Context c) {
      super(c);
      d = density(c);
      normal.setColor(MUTED);
      normal.setTextSize(19 * d);
      normal.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
      highlight.setColor(INK);
      highlight.setTextSize(21 * d);
      highlight.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
      setContentDescription("歌词");
    }

    void message(String s) {
      texts.clear();
      times.clear();
      layouts.clear();
      current = -1;
      display = 0;
      message = s;
      invalidate();
    }

    void data(JSONArray lines, String plain, String empty) {
      texts.clear();
      times.clear();
      manualUntil = 0;
      current = -1;
      display = 0;
      if (lines != null)
        for (int i = 0; i < lines.length(); i++) {
          JSONObject o = lines.optJSONObject(i);
          if (o != null) {
            texts.add(o.optString("text"));
            times.add(o.optInt("time"));
          }
        }
      timed = !texts.isEmpty();
      if (texts.isEmpty() && plain.length() > 0)
        for (String s : plain.split("\n")) {
          if (s.trim().length() > 0) {
            texts.add(s.trim());
            times.add(0);
          }
        }
      message = empty;
      layoutWidth = -1;
      invalidate();
    }

    void position(int millis) {
      if (texts.isEmpty()) return;
      int next = -1;
      if (timed) {
        int lo = 0, hi = times.size() - 1;
        while (lo <= hi) {
          int mid = (lo + hi) / 2;
          if (times.get(mid) <= millis) {
            next = mid;
            lo = mid + 1;
          } else hi = mid - 1;
        }
      }
      boolean change = current != next;
      current = next;
      if (System.currentTimeMillis() > manualUntil && timed && display != Math.max(0, current)) {
        display = Math.max(0, current);
        change = true;
      }
      if (change) invalidate();
    }

    int currentLine() {
      return current;
    }

    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
      layoutWidth = -1;
    }

    private void measureLines() {
      int width = Math.max(1, getWidth() - (int) (28 * d));
      if (width == layoutWidth) return;
      layoutWidth = width;
      layouts.clear();
      for (String s : texts)
        layouts.add(
            new StaticLayout(s, normal, width, Layout.Alignment.ALIGN_CENTER, 1.12f, 0, false));
    }

    protected void onDraw(Canvas c) {
      if (texts.isEmpty()) {
        normal.setTextSize(18 * d);
        StaticLayout empty =
            new StaticLayout(
                message,
                normal,
                Math.max(1, getWidth() - (int) (40 * d)),
                Layout.Alignment.ALIGN_CENTER,
                1.6f,
                0,
                false);
        c.save();
        c.translate(20 * d, (getHeight() - empty.getHeight()) / 2f);
        empty.draw(c);
        c.restore();
        normal.setTextSize(19 * d);
        return;
      }
      measureLines();
      float center = getHeight() * .48f, y = center;
      int first = Math.max(0, display - 12), last = Math.min(texts.size() - 1, display + 12);
      for (int i = display - 1; i >= first; i--)
        y -= Math.max(40 * d, layouts.get(i).getHeight() + 17 * d);
      for (int i = first; i <= last; i++) {
        StaticLayout line = layouts.get(i);
        float row = Math.max(40 * d, line.getHeight() + 17 * d);
        if (y + row > 0 && y < getHeight()) {
          TextPaint p = i == current ? highlight : normal;
          float edge = Math.min(Math.max(0, y + row / 2), Math.max(0, getHeight() - y - row / 2));
          p.setAlpha((int) (255 * Math.min(1, .25f + edge / (65 * d))));
          StaticLayout draw =
              i == current
                  ? new StaticLayout(
                      texts.get(i), p, layoutWidth, Layout.Alignment.ALIGN_CENTER, 1.1f, 0, false)
                  : line;
          c.save();
          c.translate((getWidth() - layoutWidth) / 2f, y - draw.getHeight() / 2f);
          draw.draw(c);
          c.restore();
          p.setAlpha(255);
        }
        y += row;
      }
    }

    public boolean onTouchEvent(android.view.MotionEvent e) {
      if (texts.isEmpty()) return super.onTouchEvent(e);
      if (e.getAction() == MotionEvent.ACTION_DOWN) {
        startY = e.getY();
        startDisplay = display;
        getParent().requestDisallowInterceptTouchEvent(true);
        return true;
      }
      if (e.getAction() == MotionEvent.ACTION_MOVE) {
        display =
            Math.max(
                0,
                Math.min(
                    texts.size() - 1, startDisplay + Math.round((startY - e.getY()) / (44 * d))));
        manualUntil = System.currentTimeMillis() + 5000;
        invalidate();
        return true;
      }
      if (e.getAction() == MotionEvent.ACTION_UP) {
        performClick();
        return true;
      }
      return true;
    }

    public boolean performClick() {
      super.performClick();
      return true;
    }
  }
}
