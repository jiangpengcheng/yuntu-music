package io.github.yuntumusic.direct;

import android.app.*;
import android.content.*;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.*;
import org.json.*;

public final class MainActivity extends Activity implements PlaybackService.Listener {
  private static final int BG = PlayerViews.BG,
      PANEL = PlayerViews.PANEL,
      INK = PlayerViews.INK,
      MUTED = PlayerViews.MUTED,
      ACCENT = PlayerViews.RED;
  private PlaybackService service;
  private boolean bound = false, visible = false;
  private static boolean cleanupStarted;
  private final Handler handler = new Handler();
  private LinearLayout content;
  private TextView nowTitle, nowState, time;
  private PlayerViews.Icon play, largePlay;
  private PlayerViews.Icon mode, likeButton, saveButton;
  private ArrayList<Track> fmItems = new ArrayList<Track>();
  private boolean liked = false, likeBusy = false;
  private int likeVersion = 0, pickerVersion = 0;
  private Dialog saveDialog;
  private Toast activeToast;
  private PlayerViews.Art miniArt, albumArt;
  private PlayerViews.Progress bottomProgress;
  private PlayerViews.Lyrics lyricsView;
  private TextView songTitle, songArtist, elapsed, total, accountLabel, playerStatus;
  private final ArrayList<TextView> navLabels = new ArrayList<TextView>();
  private String navSection = "当前播放",
      displayedId = "",
      coverUrl = "",
      lyricPlain = "",
      lyricEmpty = "暂无歌词";
  private JSONArray lyricLines = new JSONArray();
  private int presentationVersion = 0;
  private boolean compact;
  private int albumSize;
  private SeekBar seek;
  private boolean scrubbing = false;
  private String section = "当前播放";
  private int pageVersion = 0;
  private ListView queueList;
  private BaseAdapter queueAdapter;
  private Button queueRetry;
  private final ArrayList<Track> queueItems = new ArrayList<Track>();
  private int shownQueueRevision = -1;
  private String shownQueueCurrent = "";
  private boolean locateQueueCurrent;
  private ArrayList<Track> shownTracks = new ArrayList<Track>();
  private String pagePath = "";
  private boolean pageMore = false;
  private String searchQuery = "", searchType = "song";
  private EditText searchInput;
  private final ArrayList<TextView> searchTabs = new ArrayList<TextView>();
  private boolean searchDetail = false;
  private LinearLayout rows;
  private TextView pageStatus;
  private AlertDialog qrDialog;
  private int qrVersion = 0;
  private final ServiceConnection connection =
      new ServiceConnection() {
        public void onServiceConnected(ComponentName n, IBinder b) {
          service = ((PlaybackService.LocalBinder) b).get();
          bound = true;
          service.setListener(MainActivity.this);
          service.setUiVisible(visible);
          service.startAfterLaunch();
          changed();
        }

        public void onServiceDisconnected(ComponentName n) {
          service = null;
          bound = false;
        }
      };
  private final Runnable tick =
      new Runnable() {
        public void run() {
          if (!visible) return;
          updateProgress();
          handler.postDelayed(this, 1000);
        }
      };

  public void onCreate(Bundle b) {
    super.onCreate(b);
    removeOldOverlayLogs();
    setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
    getWindow()
        .setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
    build();
    bindService(new Intent(this, PlaybackService.class), connection, BIND_AUTO_CREATE);
    // Native transport needs no gateway setup.
    nowPlaying();
  }

  private void removeOldOverlayLogs() {
    if (cleanupStarted || Api.prefs(this).getBoolean("overlayLogsRemoved", false)) return;
    cleanupStarted = true;
    final Context app = getApplicationContext();
    new Thread(
            new Runnable() {
              public void run() {
                try {
                  boolean ok = true;
                  for (String name :
                      new String[] {
                        "overlay-diagnostics.jsonl", "overlay-diagnostics.previous.jsonl"
                      }) {
                    java.io.File file = new java.io.File(app.getFilesDir(), name);
                    if (file.exists() && !file.delete()) ok = false;
                  }
                  java.io.File dir = app.getExternalFilesDir("diagnostics");
                  if (dir == null) return;
                  java.io.File exported = new java.io.File(dir, "yuntu-overlay-log.txt");
                  if (exported.exists() && !exported.delete()) ok = false;
                  if (ok) Api.prefs(app).edit().putBoolean("overlayLogsRemoved", true).apply();
                } catch (RuntimeException ignored) {
                }
              }
            },
            "YuntuCleanup")
        .start();
  }

  public void onResume() {
    super.onResume();
    visible = true;
    if (service != null) service.setUiVisible(true);
    keepScreen();
    handler.post(tick);
  }

  public void onPause() {
    super.onPause();
    visible = false;
    if (service != null) service.setUiVisible(false);
    handler.removeCallbacks(tick);
    if (qrDialog != null) qrDialog.dismiss();
  }

  public void onStop() {
    if (service != null) service.setUiVisible(false);
    super.onStop();
  }

  public void onDestroy() {
    pageVersion++;
    qrVersion++;
    presentationVersion++;
    likeVersion++;
    pickerVersion++;
    if (saveDialog != null) saveDialog.dismiss();
    if (activeToast != null) activeToast.cancel();
    handler.removeCallbacksAndMessages(null);
    if (service != null) service.setListener(null);
    if (bound) unbindService(connection);
    super.onDestroy();
  }

  private int dp(float n) {
    return (int) (getResources().getDisplayMetrics().density * n + .5f);
  }

  private GradientDrawable bg(int color) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(9));
    return d;
  }

  private TextView text(String s, int size, int color) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setGravity(Gravity.CENTER_VERTICAL);
    return t;
  }

  private Button button(String s, final Runnable r) {
    Button b = new Button(this);
    b.setText(s);
    b.setTextSize(16);
    b.setTextColor(INK);
    b.setAllCaps(false);
    b.setBackground(bg(0xff25252d));
    b.setMinHeight(dp(48));
    b.setPadding(dp(10), 0, dp(10), 0);
    b.setOnClickListener(
        new View.OnClickListener() {
          public void onClick(View v) {
            r.run();
          }
        });
    return b;
  }

  private LinearLayout vertical() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private LinearLayout.LayoutParams weighted() {
    return new LinearLayout.LayoutParams(0, -1, 1);
  }

  private void immersive() {
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
  }

  public void onWindowFocusChanged(boolean focus) {
    super.onWindowFocusChanged(focus);
    if (focus) immersive();
  }

  private void build() {
    immersive();
    android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
    getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
    float height = metrics.heightPixels / metrics.density;
    float width = metrics.widthPixels / metrics.density;
    compact = height < 480;
    albumSize = (int) Math.max(120, Math.min(width * .29f, height - (compact ? 240 : 282)));
    LinearLayout root = new LinearLayout(this);
    root.setBackgroundColor(BG);
    setContentView(root);
    LinearLayout nav = vertical();
    nav.setGravity(Gravity.CENTER_HORIZONTAL);
    nav.setBackgroundColor(PANEL);
    nav.setPadding(dp(8), dp(compact ? 12 : 18), dp(8), dp(10));
    root.addView(nav, new LinearLayout.LayoutParams(dp(compact ? 82 : 94), -1));
    PlayerViews.Art logo = new PlayerViews.Art(this, true);
    logo.bitmap(BitmapFactory.decodeResource(getResources(), R.drawable.ic_launcher));
    LinearLayout.LayoutParams logoParams =
        new LinearLayout.LayoutParams(dp(compact ? 42 : 48), dp(compact ? 42 : 48));
    logoParams.bottomMargin = dp(compact ? 14 : 20);
    nav.addView(logo, logoParams);
    String[] labels = {"当前播放", "歌单", "FM", "搜索", "设置"};
    for (int i = 0; i < labels.length; i++) {
      final int which = i;
      TextView item = text(labels[i], compact ? 14 : 15, MUTED);
      item.setGravity(Gravity.CENTER);
      item.setFocusable(true);
      item.setContentDescription(labels[i]);
      item.setOnClickListener(
          new View.OnClickListener() {
            public void onClick(View v) {
              if (which == 0) nowPlaying();
              else if (which == 1) playlists();
              else if (which == 2) fm();
              else if (which == 3) search();
              else account();
            }
          });
      navLabels.add(item);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(compact ? 44 : 55));
      lp.bottomMargin = dp(compact ? 4 : 10);
      nav.addView(item, lp);
    }
    nav.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
    accountLabel = text("云途 · 随心听", 11, 0xff61616e);
    accountLabel.setGravity(Gravity.CENTER);
    accountLabel.setSingleLine(true);
    accountLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
    nav.addView(accountLabel, new LinearLayout.LayoutParams(-1, dp(24)));
    View divider = new View(this);
    divider.setBackgroundColor(LINE_COLOR());
    root.addView(divider, new LinearLayout.LayoutParams(dp(1), -1));
    LinearLayout right = vertical();
    root.addView(right, new LinearLayout.LayoutParams(0, -1, 1));
    content = vertical();
    right.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout bottom = vertical();
    bottom.setBackgroundColor(0xff131318);
    right.addView(bottom);
    bottomProgress = new PlayerViews.Progress(this);
    bottom.addView(bottomProgress, new LinearLayout.LayoutParams(-1, dp(3)));
    LinearLayout bar = new LinearLayout(this);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.setPadding(dp(18), 0, dp(18), 0);
    bottom.addView(bar, new LinearLayout.LayoutParams(-1, dp(compact ? 62 : 76)));
    miniArt = new PlayerViews.Art(this, false);
    bar.addView(
        miniArt, new LinearLayout.LayoutParams(dp(compact ? 42 : 50), dp(compact ? 42 : 50)));
    miniArt.setOnClickListener(
        new View.OnClickListener() {
          public void onClick(View v) {
            nowPlaying();
          }
        });
    LinearLayout info = vertical();
    info.setGravity(Gravity.CENTER_VERTICAL);
    info.setPadding(dp(14), 0, dp(10), 0);
    nowTitle = text("云途音乐", compact ? 16 : 18, INK);
    nowTitle.setTypeface(null, Typeface.BOLD);
    nowTitle.setSingleLine(true);
    nowTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
    nowState = text("选一首歌，开始旅途", 12, MUTED);
    nowState.setSingleLine(true);
    nowState.setEllipsize(android.text.TextUtils.TruncateAt.END);
    info.addView(nowTitle);
    info.addView(nowState);
    bar.addView(info, new LinearLayout.LayoutParams(0, -1, 1));
    info.setOnClickListener(
        new View.OnClickListener() {
          public void onClick(View v) {
            nowPlaying();
          }
        });
    time = text("0:00 / 0:00", 12, MUTED);
    time.setGravity(Gravity.CENTER);
    bar.addView(time, new LinearLayout.LayoutParams(dp(88), -1));
    addIcon(
        bar,
        "previous",
        "上一首",
        new Runnable() {
          public void run() {
            if (service != null) service.previous();
          }
        },
        compact ? 42 : 50);
    play =
        addIcon(
            bar,
            "play",
            "播放",
            new Runnable() {
              public void run() {
                togglePlayback();
              }
            },
            compact ? 42 : 50);
    addIcon(
        bar,
        "next",
        "下一首",
        new Runnable() {
          public void run() {
            if (service != null) service.next(true);
          }
        },
        compact ? 42 : 50);
    likeButton =
        addActionIcon(
            bar,
            "heart",
            "喜欢当前歌曲",
            new Runnable() {
              public void run() {
                toggleLike();
              }
            });
    saveButton =
        addActionIcon(
            bar,
            "save",
            "收藏到歌单",
            new Runnable() {
              public void run() {
                choosePlaylist();
              }
            });
    mode =
        addActionIcon(
            bar,
            "order",
            "顺序播放",
            new Runnable() {
              public void run() {
                if (service != null) service.cycleMode();
              }
            });
    addActionIcon(
        bar,
        "queue",
        "播放列表",
        new Runnable() {
          public void run() {
            queue();
          }
        });
    nowPlaying();
  }

  private int LINE_COLOR() {
    return PlayerViews.LINE;
  }

  private void togglePlayback() {
    if (service != null && service.current() != null) service.toggle();
    else if (section.equals("私人 FM") && !fmItems.isEmpty()) playFm(0);
    else playlists();
  }

  private PlayerViews.Icon addIcon(
      LinearLayout parent, String kind, String label, Runnable action, int size) {
    PlayerViews.Icon view = new PlayerViews.Icon(this, kind, label, action);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(size), dp(size));
    lp.leftMargin = dp(12);
    parent.addView(view, lp);
    return view;
  }

  private PlayerViews.Icon addActionIcon(
      LinearLayout parent, String kind, final String label, Runnable action) {
    final PlayerViews.Icon icon = addIcon(parent, kind, label, action, 44);
    icon.bare = true;
    ((LinearLayout.LayoutParams) icon.getLayoutParams()).leftMargin = dp(compact ? 3 : 5);
    icon.setOnLongClickListener(
        new View.OnLongClickListener() {
          public boolean onLongClick(View v) {
            toast(icon.getContentDescription().toString());
            return true;
          }
        });
    return icon;
  }

  private void selectNav(String selected) {
    navSection = selected;
    String[] keys = {"当前播放", "歌单", "FM", "搜索", "设置"};
    for (int i = 0; i < navLabels.size(); i++) {
      TextView item = navLabels.get(i);
      boolean active = keys[i].equals(selected);
      item.setTextColor(active ? ACCENT : MUTED);
      item.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
      item.setBackground(bg(active ? 0xff331c23 : PANEL));
    }
  }

  private void clearPage() {
    searchDetail = false;
    pageVersion++;
    content.removeAllViews();
    queueList = null;
    queueAdapter = null;
    queueRetry = null;
    queueItems.clear();
    shownQueueRevision = -1;
    shownQueueCurrent = "";
    locateQueueCurrent = false;
    seek = null;
    largePlay = null;
    albumArt = null;
    songTitle = null;
    songArtist = null;
    lyricsView = null;
    elapsed = null;
    total = null;
    playerStatus = null;
  }

  private void page(String title) {
    clearPage();
    content.setPadding(dp(26), dp(compact ? 16 : 24), dp(26), dp(12));
    TextView h = text(title, compact ? 24 : 28, INK);
    h.setTypeface(null, Typeface.BOLD);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(50));
    lp.bottomMargin = dp(12);
    content.addView(h, lp);
  }

  private void nowPlaying() {
    section = "当前播放";
    selectNav("当前播放");
    clearPage();
    content.setPadding(dp(compact ? 20 : 26), dp(compact ? 14 : 24), dp(compact ? 20 : 32), dp(8));
    LinearLayout body = new LinearLayout(this);
    content.addView(body, new LinearLayout.LayoutParams(-1, -1));
    LinearLayout left = vertical();
    left.setGravity(Gravity.CENTER_HORIZONTAL);
    body.addView(
        left, new LinearLayout.LayoutParams(dp(Math.max(albumSize, compact ? 168 : 216)), -1));
    left.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
    albumArt = new PlayerViews.Art(this, false);
    left.addView(albumArt, new LinearLayout.LayoutParams(dp(albumSize), dp(albumSize)));
    LinearLayout clocks = new LinearLayout(this);
    clocks.setPadding(0, dp(compact ? 8 : 14), 0, 0);
    elapsed = text("0:00", 12, MUTED);
    total = text("0:00", 12, MUTED);
    total.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
    clocks.addView(elapsed, new LinearLayout.LayoutParams(0, dp(24), 1));
    clocks.addView(total, new LinearLayout.LayoutParams(0, dp(24), 1));
    left.addView(clocks);
    seek = (SeekBar) getLayoutInflater().inflate(R.layout.seek, null);
    seek.setMax(1000);
    seek.setPadding(dp(9), 0, dp(9), 0);
    seek.setThumbOffset(dp(9));
    android.graphics.drawable.GradientDrawable thumb = bg(ACCENT);
    thumb.setShape(android.graphics.drawable.GradientDrawable.OVAL);
    thumb.setSize(dp(17), dp(17));
    seek.setThumb(thumb);
    android.graphics.drawable.GradientDrawable track = bg(LINE_COLOR()), fill = bg(ACCENT);
    android.graphics.drawable.ClipDrawable clip =
        new android.graphics.drawable.ClipDrawable(
            fill, Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL);
    android.graphics.drawable.LayerDrawable layers =
        new android.graphics.drawable.LayerDrawable(
            new android.graphics.drawable.Drawable[] {track, clip});
    layers.setId(0, android.R.id.background);
    layers.setId(1, android.R.id.progress);
    seek.setProgressDrawable(layers);
    left.addView(seek, new LinearLayout.LayoutParams(-1, dp(30)));
    seek.setContentDescription("播放进度");
    seek.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onStartTrackingTouch(SeekBar s) {
            scrubbing = true;
          }

          public void onProgressChanged(SeekBar s, int progress, boolean user) {
            if (user && service != null && elapsed != null)
              elapsed.setText(clock((int) ((long) service.duration() * progress / 1000)));
          }

          public void onStopTrackingTouch(SeekBar s) {
            scrubbing = false;
            if (service != null)
              service.seek((int) ((long) service.duration() * s.getProgress() / 1000));
          }
        });
    LinearLayout controls = new LinearLayout(this);
    controls.setGravity(Gravity.CENTER);
    int size = compact ? 48 : 64;
    addIcon(
        controls,
        "previous",
        "上一首",
        new Runnable() {
          public void run() {
            if (service != null) service.previous();
          }
        },
        size);
    largePlay =
        addIcon(
            controls,
            "play",
            "播放",
            new Runnable() {
              public void run() {
                togglePlayback();
              }
            },
            size);
    largePlay.active = true;
    addIcon(
        controls,
        "next",
        "下一首",
        new Runnable() {
          public void run() {
            if (service != null) service.next(true);
          }
        },
        size);
    // Remove the leading icon margin to center the complete group under the artwork.
    ((LinearLayout.LayoutParams) controls.getChildAt(0).getLayoutParams()).leftMargin = 0;
    left.addView(controls, new LinearLayout.LayoutParams(-1, dp(size + 4)));
    left.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
    LinearLayout right = vertical();
    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, -1, 1);
    rlp.leftMargin = dp(compact ? 30 : 42);
    body.addView(right, rlp);
    songTitle = text("让喜欢的音乐，一路相伴", compact ? 23 : 28, INK);
    songTitle.setTypeface(null, Typeface.BOLD);
    songTitle.setSingleLine(true);
    songTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
    right.addView(songTitle, new LinearLayout.LayoutParams(-1, dp(compact ? 36 : 43)));
    songArtist = text("云途音乐", compact ? 15 : 18, MUTED);
    songArtist.setSingleLine(true);
    songArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
    right.addView(songArtist, new LinearLayout.LayoutParams(-1, dp(30)));
    playerStatus = text("歌单 / FM / 搜索", 11, 0xff63636f);
    playerStatus.setSingleLine(true);
    playerStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
    right.addView(playerStatus, new LinearLayout.LayoutParams(-1, dp(24)));
    playerStatus.setOnClickListener(
        new View.OnClickListener() {
          public void onClick(View v) {
            if (service != null) toast(service.status());
          }
        });
    View line = new View(this);
    line.setBackgroundColor(LINE_COLOR());
    right.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    lyricsView = new PlayerViews.Lyrics(this);
    right.addView(lyricsView, new LinearLayout.LayoutParams(-1, 0, 1));
    lyricsView.setOnClickListener(
        new View.OnClickListener() {
          public void onClick(View v) {
            if (lyricEmpty.contains("重试") && service != null && service.current() != null)
              loadPresentation(service.current());
          }
        });
    ArtworkLoader.load(albumArt, coverUrl, 480);
    renderLyrics();
    changed();
  }

  private void renderLyrics() {
    if (lyricsView != null) lyricsView.data(lyricLines, lyricPlain, lyricEmpty);
  }

  private void loadPresentation(final Track track) {
    final int version = ++presentationVersion;
    lyricLines = new JSONArray();
    lyricPlain = "";
    lyricEmpty = "正在加载歌词…";
    coverUrl = track.cover;
    if (albumArt != null) ArtworkLoader.load(albumArt, coverUrl, 480);
    ArtworkLoader.load(miniArt, coverUrl, 160);
    renderLyrics();
    Api.request(
        this,
        "GET",
        "/v1/songs/" + track.id + "/presentation",
        new Api.Callback() {
          public void done(JSONObject o, String error) {
            if (version != presentationVersion
                || service == null
                || service.current() == null
                || !track.id.equals(service.current().id)) return;
            if (error != null) {
              lyricEmpty = "歌词暂时不可用，点击重试";
              renderLyrics();
              return;
            }
            lyricLines = o.optJSONArray("lines");
            lyricPlain = o.optString("plain");
            lyricEmpty =
                o.optBoolean("lyricError")
                    ? "歌词暂时不可用，点击重试"
                    : o.optBoolean("instrumental") ? "纯音乐，请欣赏" : "暂无歌词，静静聆听";
            String art = o.optString("cover");
            if (art.length() > 0 && !art.equals(coverUrl)) {
              coverUrl = art;
              if (albumArt != null) ArtworkLoader.load(albumArt, coverUrl, 480);
              ArtworkLoader.load(miniArt, coverUrl, 160);
            }
            renderLyrics();
            updateProgress();
          }
        });
  }

  private void message(String s) {
    TextView t = text(s, 17, MUTED);
    t.setPadding(dp(8), dp(12), dp(8), dp(12));
    content.addView(t);
  }

  private void toast(String s) {
    if (activeToast != null) activeToast.cancel();
    activeToast = Toast.makeText(this, s, Toast.LENGTH_LONG);
    activeToast.show();
  }

  private void scroller() {
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(false);
    rows = vertical();
    scroll.addView(rows);
    content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    pageStatus = text("加载中…", 13, MUTED);
    content.addView(pageStatus, new LinearLayout.LayoutParams(-1, dp(28)));
  }

  private void row(LinearLayout parent, String title, String subtitle, final Runnable action) {
    LinearLayout box = vertical();
    box.setPadding(dp(16), dp(9), dp(12), dp(9));
    box.setBackground(bg(0xff19191f));
    TextView name = text(title, 18, INK);
    name.setSingleLine(true);
    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
    box.addView(name);
    TextView sub = text(subtitle, 13, MUTED);
    sub.setSingleLine(true);
    sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
    box.addView(sub);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(68));
    lp.bottomMargin = dp(7);
    parent.addView(box, lp);
    box.setOnClickListener(
        new View.OnClickListener() {
          public void onClick(View v) {
            action.run();
          }
        });
  }

  private void playlists() {
    section = "歌单";
    selectNav("歌单");
    page("我的歌单");
    scroller();
    loadPlaylists(0, pageVersion);
  }

  private void loadPlaylists(final int offset, final int version) {
    final LinearLayout target = rows;
    final TextView status = pageStatus;
    Api.request(
        this,
        "GET",
        "/v1/playlists?offset=" + offset,
        new Api.Callback() {
          public void done(JSONObject o, String error) {
            if (version != pageVersion) return;
            if (error != null) {
              status.setText(error);
              if (offset == 0)
                row(
                    target,
                    "连接 / 登录",
                    "打开账号和设置",
                    new Runnable() {
                      public void run() {
                        account();
                      }
                    });
              return;
            }
            try {
              JSONArray a = o.getJSONArray("items");
              for (int i = 0; i < a.length(); i++) {
                final JSONObject p = a.getJSONObject(i);
                row(
                    target,
                    p.getString("name"),
                    p.optInt("count") + " 首歌曲",
                    new Runnable() {
                      public void run() {
                        tracks(
                            p.optString("name"), "/v1/playlists/" + p.optString("id") + "/tracks");
                      }
                    });
              }
              status.setText("已加载 " + (offset + a.length()) + " 个歌单");
              if (o.optBoolean("more")) {
                final int next = offset + a.length();
                final Button[] more = new Button[1];
                more[0] =
                    button(
                        "加载更多歌单",
                        new Runnable() {
                          public void run() {
                            target.removeView(more[0]);
                            loadPlaylists(next, version);
                          }
                        });
                target.addView(more[0]);
              }
            } catch (Exception e) {
              status.setText("歌单数据异常，请重试");
            }
          }
        });
  }

  private void tracks(String title, String path) {
    section = title;
    page(title);
    shownTracks = new ArrayList<Track>();
    pagePath = path;
    pageMore = false;
    scroller();
    loadTracks(0, pageVersion);
  }

  private void loadTracks(final int offset, final int version) {
    final LinearLayout target = rows;
    final TextView status = pageStatus;
    Api.request(
        this,
        "GET",
        pagePath + (pagePath.indexOf('?') >= 0 ? "&" : "?") + "offset=" + offset,
        new Api.Callback() {
          public void done(JSONObject o, String error) {
            if (version != pageVersion) return;
            if (error != null) {
              status.setText(error);
              return;
            }
            try {
              ArrayList<Track> add = Track.parse(o.optJSONArray("items"));
              for (final Track t : add) {
                final int at = shownTracks.size();
                shownTracks.add(t);
                row(
                    target,
                    t.name,
                    t.artist + " · " + t.album,
                    new Runnable() {
                      public void run() {
                        if (service != null)
                          service.playQueue(
                              new ArrayList<Track>(shownTracks),
                              at,
                              false,
                              pagePath,
                              shownTracks.size(),
                              pageMore);
                        nowPlaying();
                      }
                    });
              }
              pageMore = o.optBoolean("more");
              status.setText(
                  shownTracks.isEmpty()
                      ? "没有找到歌曲"
                      : "已加载 "
                          + shownTracks.size()
                          + " 首"
                          + (pagePath.startsWith("/v1/playlists/")
                              ? " · 播放后自动加载完整歌单"
                              : " · 顺序播放时自动续载"));
              if (pageMore && !add.isEmpty()) {
                final Button[] more = new Button[1];
                more[0] =
                    button(
                        "加载更多歌曲",
                        new Runnable() {
                          public void run() {
                            target.removeView(more[0]);
                            loadTracks(shownTracks.size(), version);
                          }
                        });
                target.addView(more[0]);
              }
            } catch (Exception e) {
              status.setText("歌曲数据异常，请重试");
            }
          }
        });
  }

  private EditText edit(String hint, String value) {
    EditText e = new EditText(this);
    e.setTextColor(INK);
    e.setHintTextColor(MUTED);
    e.setTextSize(18);
    e.setSingleLine(true);
    e.setHint(hint);
    e.setText(value);
    return e;
  }

  private void search() {
    section = "搜索";
    selectNav("搜索");
    page("搜索音乐");
    searchInput = edit("搜索歌曲、歌手、专辑或歌单", searchQuery);
    searchInput.setContentDescription("搜索关键词");
    searchInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
    LinearLayout line = new LinearLayout(this);
    line.setGravity(Gravity.CENTER_VERTICAL);
    line.addView(searchInput, new LinearLayout.LayoutParams(0, dp(48), 1));
    final Runnable submit =
        new Runnable() {
          public void run() {
            submitSearch();
          }
        };
    Button go = button("搜索", submit);
    go.setContentDescription("执行搜索");
    go.setTextColor(ACCENT);
    line.addView(go, new LinearLayout.LayoutParams(dp(90), dp(44)));
    content.addView(line);
    LinearLayout tabs = new LinearLayout(this);
    tabs.setPadding(0, dp(8), 0, dp(10));
    searchTabs.clear();
    String[] labels = {"歌曲", "歌手", "专辑", "歌单"};
    final String[] kinds = {"song", "artist", "album", "playlist"};
    for (int i = 0; i < labels.length; i++) {
      final String kind = kinds[i];
      TextView tab = text(labels[i], 15, MUTED);
      tab.setGravity(Gravity.CENTER);
      tab.setFocusable(true);
      tab.setContentDescription("搜索分类：" + labels[i]);
      tab.setOnClickListener(
          new View.OnClickListener() {
            public void onClick(View v) {
              searchType = kind;
              submitSearch();
            }
          });
      searchTabs.add(tab);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(compact ? 78 : 92), dp(38));
      lp.rightMargin = dp(8);
      tabs.addView(tab, lp);
    }
    content.addView(tabs);
    scroller();
    searchInput.setOnEditorActionListener(
        new TextView.OnEditorActionListener() {
          public boolean onEditorAction(TextView t, int action, KeyEvent e) {
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
              submit.run();
              return true;
            }
            return false;
          }
        });
    submitSearch();
  }

  private void submitSearch() {
    String query = searchInput.getText().toString().trim();
    searchQuery = query;
    ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
        .hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
    pageVersion++;
    rows.removeAllViews();
    shownTracks = new ArrayList<Track>();
    pageMore = false;
    String[] kinds = {"song", "artist", "album", "playlist"};
    for (int i = 0; i < searchTabs.size(); i++) {
      boolean selected = kinds[i].equals(searchType);
      TextView tab = searchTabs.get(i);
      tab.setTextColor(selected ? ACCENT : MUTED);
      tab.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
      tab.setBackground(bg(selected ? 0xff331c23 : BG));
    }
    if (query.length() == 0) {
      pageStatus.setText("输入关键词，再选择搜索分类");
      return;
    }
    if (query.length() > 100) {
      pageStatus.setText("搜索内容请控制在 100 个字符以内");
      return;
    }
    pageStatus.setText("正在搜索…");
    if (searchType.equals("song")) {
      pagePath = "/v1/search?type=song&q=" + Api.encode(query);
      loadTracks(0, pageVersion);
    } else loadSearchEntities(query, searchType, 0, pageVersion);
  }

  private void loadSearchEntities(
      final String query, final String kind, final int offset, final int version) {
    final LinearLayout target = rows;
    final TextView status = pageStatus;
    Api.request(
        this,
        "GET",
        "/v1/search?q=" + Api.encode(query) + "&type=" + kind + "&offset=" + offset,
        new Api.Callback() {
          public void done(JSONObject o, String error) {
            if (version != pageVersion) return;
            if (error != null) {
              status.setText(error);
              return;
            }
            JSONArray items = o.optJSONArray("items");
            int count = items == null ? 0 : items.length();
            for (int i = 0; i < count; i++) {
              final JSONObject item = items.optJSONObject(i);
              if (item == null) continue;
              catalogRow(
                  target,
                  item,
                  kind,
                  new Runnable() {
                    public void run() {
                      openSearchEntity(kind, item);
                    }
                  });
            }
            status.setText(
                offset + count == 0
                    ? "没有找到结果，试试其他关键词或分类"
                    : "已加载 " + (offset + count) + " 个结果 · 点击查看歌曲");
            final int next = o.optInt("nextOffset", offset + count);
            if (o.optBoolean("more") && next > offset) {
              final Button[] more = new Button[1];
              more[0] =
                  button(
                      "加载更多结果",
                      new Runnable() {
                        public void run() {
                          target.removeView(more[0]);
                          loadSearchEntities(query, kind, next, version);
                        }
                      });
              target.addView(more[0]);
            }
          }
        });
  }

  private void catalogRow(
      LinearLayout parent, JSONObject item, String kind, final Runnable action) {
    LinearLayout box = new LinearLayout(this);
    box.setGravity(Gravity.CENTER_VERTICAL);
    box.setPadding(dp(12), dp(8), dp(16), dp(8));
    box.setBackground(bg(0xff19191f));
    PlayerViews.Art art = new PlayerViews.Art(this, false);
    box.addView(art, new LinearLayout.LayoutParams(dp(48), dp(48)));
    ArtworkLoader.load(art, item.optString("cover"), 120);
    LinearLayout info = vertical();
    info.setPadding(dp(14), 0, dp(10), 0);
    TextView name = text(item.optString("name"), 18, INK);
    name.setSingleLine(true);
    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
    info.addView(name);
    TextView sub = text(item.optString("subtitle"), 12, MUTED);
    sub.setSingleLine(true);
    sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
    info.addView(sub);
    box.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
    box.addView(text("›", 24, MUTED));
    box.setContentDescription(
        "查看"
            + (kind.equals("artist") ? "歌手" : kind.equals("album") ? "专辑" : "歌单")
            + "："
            + item.optString("name"));
    box.setFocusable(true);
    box.setOnClickListener(
        new View.OnClickListener() {
          public void onClick(View v) {
            action.run();
          }
        });
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(68));
    lp.bottomMargin = dp(7);
    parent.addView(box, lp);
  }

  private void openSearchEntity(String kind, JSONObject item) {
    String group =
        kind.equals("artist") ? "artists" : kind.equals("album") ? "albums" : "playlists";
    tracks(item.optString("name"), "/v1/" + group + "/" + item.optString("id") + "/tracks");
    searchDetail = true;
    TextView back = text("‹ 返回搜索结果", 14, ACCENT);
    back.setFocusable(true);
    back.setOnClickListener(
        new View.OnClickListener() {
          public void onClick(View v) {
            search();
          }
        });
    content.addView(back, 0, new LinearLayout.LayoutParams(-1, dp(32)));
  }

  public void onBackPressed() {
    if (searchDetail) search();
    else super.onBackPressed();
  }

  private void fm() {
    section = "私人 FM";
    selectNav("FM");
    page("私人 FM");
    fmItems = new ArrayList<Track>();
    final LinearLayout actions = new LinearLayout(this);
    final Button start =
        button(
            "播放全部",
            new Runnable() {
              public void run() {
                playFm(0);
              }
            });
    start.setEnabled(false);
    start.setTextColor(ACCENT);
    actions.addView(start, new LinearLayout.LayoutParams(dp(140), dp(48)));
    Button refresh =
        button(
            "换一批",
            new Runnable() {
              public void run() {
                fm();
              }
            });
    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(110), dp(48));
    rp.leftMargin = dp(12);
    actions.addView(refresh, rp);
    content.addView(actions);
    scroller();
    pageStatus.setText("正在加载，目标 " + Api.fmCount(this) + " 首…");
    final int version = pageVersion;
    final LinearLayout target = rows;
    final TextView status = pageStatus;
    Api.request(
        this,
        "GET",
        "/v1/fm?limit=" + Api.fmCount(this),
        new Api.Callback() {
          public void done(JSONObject o, String error) {
            if (version != pageVersion) return;
            if (error != null) {
              status.setText(error);
              return;
            }
            try {
              fmItems = Track.parse(o.optJSONArray("items"));
              for (int i = 0; i < fmItems.size(); i++) {
                final int at = i;
                Track t = fmItems.get(i);
                row(
                    target,
                    (i + 1) + "  " + t.name,
                    t.artist + " · " + t.album,
                    new Runnable() {
                      public void run() {
                        playFm(at);
                      }
                    });
              }
              start.setEnabled(!fmItems.isEmpty());
              start.setText("播放全部 · " + fmItems.size() + " 首");
              status.setText(
                  fmItems.isEmpty()
                      ? "暂时没有推荐，点换一批重试"
                      : o.optBoolean("partial")
                          ? "本次获得 "
                              + fmItems.size()
                              + " / "
                              + o.optInt("requested")
                              + " 首 · 点击歌曲开始播放"
                          : "已加载 " + fmItems.size() + " 首 · 点击歌曲开始播放");
            } catch (Exception e) {
              status.setText("私人 FM 数据异常，请重试");
            }
          }
        });
  }

  private void playFm(int index) {
    if (service != null && !fmItems.isEmpty())
      service.playQueue(new ArrayList<Track>(fmItems), index, true, "", 0, false);
  }

  private void queue() {
    section = "队列";
    selectNav("");
    page("播放列表");
    locateQueueCurrent = true;
    queueList = new ListView(this);
    queueList.setDivider(new android.graphics.drawable.ColorDrawable(BG));
    queueList.setDividerHeight(dp(7));
    queueList.setCacheColorHint(BG);
    queueAdapter =
        new BaseAdapter() {
          public int getCount() {
            return queueItems.size();
          }

          public Object getItem(int at) {
            return queueItems.get(at);
          }

          public long getItemId(int at) {
            return at;
          }

          public View getView(int at, View recycled, ViewGroup parent) {
            LinearLayout box;
            TextView[] labels;
            if (recycled == null) {
              box = vertical();
              box.setPadding(dp(16), dp(9), dp(12), dp(9));
              box.setBackground(bg(0xff19191f));
              box.setLayoutParams(new AbsListView.LayoutParams(-1, dp(68)));
              labels = new TextView[] {text("", 18, INK), text("", 13, MUTED)};
              for (TextView label : labels) {
                label.setSingleLine(true);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                box.addView(label);
              }
              box.setTag(labels);
            } else {
              box = (LinearLayout) recycled;
              labels = (TextView[]) box.getTag();
            }
            Track item = queueItems.get(at);
            boolean current =
                service != null
                    && service.current() != null
                    && item.id.equals(service.current().id);
            labels[0].setText((current ? "正在播放  ·  " : (at + 1) + "  ") + item.name);
            labels[0].setTextColor(current ? ACCENT : INK);
            labels[1].setText(item.artist);
            return box;
          }
        };
    queueList.setAdapter(queueAdapter);
    queueList.setOnItemClickListener(
        new AdapterView.OnItemClickListener() {
          public void onItemClick(AdapterView<?> parent, View view, int at, long id) {
            if (service != null) {
              service.select(at);
              nowPlaying();
            }
          }
        });
    content.addView(queueList, new LinearLayout.LayoutParams(-1, 0, 1));
    queueRetry =
        button(
            "重试加载剩余歌曲",
            new Runnable() {
              public void run() {
                if (service != null) service.loadRemainingQueue();
              }
            });
    queueRetry.setVisibility(View.GONE);
    content.addView(queueRetry, new LinearLayout.LayoutParams(-1, dp(48)));
    pageStatus = text("播放器连接中", 13, MUTED);
    content.addView(pageStatus, new LinearLayout.LayoutParams(-1, dp(28)));
    renderQueue();
    if (service != null && service.queueError().length() == 0) service.loadRemainingQueue();
  }

  private void renderQueue() {
    if (!section.equals("队列") || queueAdapter == null || service == null) return;
    String current = service.current() == null ? "" : service.current().id;
    if (shownQueueRevision != service.queueRevision() || !current.equals(shownQueueCurrent)) {
      queueItems.clear();
      queueItems.addAll(service.tracks());
      shownQueueRevision = service.queueRevision();
      shownQueueCurrent = current;
      queueAdapter.notifyDataSetChanged();
    }
    if (locateQueueCurrent) {
      int currentIndex = queueItems.indexOf(service.current());
      if (currentIndex >= 0) {
        // Only locate on entry (including a late service connection), never on refresh.
        queueList.setSelectionFromTop(currentIndex, 0);
        locateQueueCurrent = false;
      }
    }
    pageStatus.setText(queueItems.size() + " 首 · " + service.queueStatus());
    queueRetry.setVisibility(
        service.queueHasMore() && !service.queueLoading() ? View.VISIBLE : View.GONE);
  }

  private void account() {
    section = "账号";
    selectNav("设置");
    page("账号与设置");
    LinearLayout actions = new LinearLayout(this);
    actions.addView(
        button(
            "播放设置 / 音质",
            new Runnable() {
              public void run() {
                settings();
              }
            }));
    actions.addView(
        button(
            "扫码登录",
            new Runnable() {
              public void run() {
                login();
              }
            }));
    actions.addView(
        button(
            "停止播放",
            new Runnable() {
              public void run() {
                if (service != null) service.stopPlayback();
              }
            }));
    content.addView(actions);
    final TextView status = text("正在检查登录状态…", 17, MUTED);
    status.setPadding(0, dp(16), 0, dp(8));
    content.addView(status);
    final int version = pageVersion;
    Api.request(
        this,
        "GET",
        "/v1/session",
        new Api.Callback() {
          public void done(JSONObject o, String error) {
            if (version != pageVersion) return;
            if (error != null) {
              status.setText(error);
              return;
            }
            if (o.optBoolean("loggedIn")) {
              status.setText("已登录：" + o.optJSONObject("profile").optString("nickname"));
              accountLabel.setText(o.optJSONObject("profile").optString("nickname"));
              content.addView(
                  button(
                      "退出网易云账号",
                      new Runnable() {
                        public void run() {
                          logout();
                        }
                      }));
            } else status.setText("尚未登录。使用手机网易云音乐扫描二维码。");
          }
        });
    String appVersionName;
    try {
      appVersionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
    } catch (Exception e) {
      appVersionName = "";
    }
    message("云途音乐 " + appVersionName + " 原生直连版 · 随心听，一路相伴\n媒体键：上一首 / 下一首 / 播放暂停");
  }

  private void logout() {
    new AlertDialog.Builder(this)
        .setTitle("退出账号")
        .setMessage("退出后将停止播放，并清除此测试版的本地登录会话。")
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "退出",
            new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface d, int w) {
                if (service != null) service.clearQueue();
                Api.request(
                    MainActivity.this,
                    "POST",
                    "/v1/logout",
                    new Api.Callback() {
                      public void done(JSONObject o, String e) {
                        if (e != null) toast(e);
                        account();
                      }
                    });
              }
            })
        .show();
  }

  private void settings() {
    final android.content.SharedPreferences prefs = Api.prefs(this);
    LinearLayout form = vertical();
    form.setPadding(dp(20), dp(6), dp(20), dp(4));
    form.addView(text("原生直连网易云 · 无需服务地址和访问口令", 14, MUTED));
    final String[] values = {"standard", "higher", "exhigh", "lossless"};
    final Spinner quality = new Spinner(this);
    quality.setAdapter(
        new ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[] {"标准 · 兼容优先", "较高", "极高 · 推荐", "无损 FLAC · 需设备支持"}));
    for (int i = 0; i < values.length; i++)
      if (values[i].equals(prefs.getString("quality", "exhigh"))) quality.setSelection(i);
    form.addView(quality);
    form.addView(text("私人 FM 每批目标数量", 14, MUTED));
    final int[] counts = {10, 20, 30, 50};
    final Spinner fmCount = new Spinner(this);
    fmCount.setAdapter(
        new ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[] {"10 首", "20 首 · 默认", "30 首", "50 首"}));
    for (int i = 0; i < counts.length; i++)
      if (counts[i] == Api.fmCount(this)) fmCount.setSelection(i);
    form.addView(fmCount);
    form.addView(text("下一次加载 FM 或自动续播时生效；实际数量取决于上游推荐。", 12, MUTED));
    final CheckBox screen = new CheckBox(this);
    screen.setText("应用在前台时保持屏幕常亮");
    screen.setChecked(prefs.getBoolean("screen", false));
    form.addView(screen);
    final CheckBox bootStart = new CheckBox(this);
    bootStart.setText("开机自启（后台启动云途）");
    bootStart.setChecked(prefs.getBoolean("bootStart", false));
    form.addView(bootStart);
    final CheckBox autoPlay = new CheckBox(this);
    autoPlay.setText("启动后自动播放上次队列");
    autoPlay.setChecked(prefs.getBoolean("autoPlay", false));
    form.addView(autoPlay);
    form.addView(
        text("下次启动生效；开机自动播放需同时开启两项。未联网时最多等待 60 秒，暂停或停止会取消自动播放。部分手机还需在系统中允许自启动。", 12, MUTED));
    final CheckBox floating = new CheckBox(this);
    floating.setText("离开应用后显示悬浮播放卡片");
    final CheckBox largeFloating = new CheckBox(this);
    largeFloating.setText("离开应用后显示大号悬浮卡片");
    final CheckBox floatingLyrics = new CheckBox(this);
    floatingLyrics.setText("离开应用后显示悬浮歌词");
    boolean compactLyrics = prefs.getBoolean("floatingLyrics", false);
    boolean full = prefs.getBoolean("floating", false) && !compactLyrics;
    boolean large = prefs.getBoolean("floatingLarge", false);
    floating.setChecked(full && !large);
    largeFloating.setChecked(full && large);
    floatingLyrics.setChecked(compactLyrics);
    final CheckBox[] modes = {floating, largeFloating, floatingLyrics};
    for (final CheckBox choice : modes) {
      choice.setOnCheckedChangeListener(
          new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton button, boolean checked) {
              if (checked)
                for (CheckBox other : modes) if (other != button) other.setChecked(false);
            }
          });
      form.addView(choice);
    }
    form.addView(text("三种样式任选其一。大号卡片与普通卡片等宽，显示三行歌词，当前句居中加粗；拖动封面或歌名移动，点击回到云途。", 12, MUTED));
    form.addView(text("悬浮歌词按文字长度自适应宽度、居中显示两行，当前句上下交替；单击进入云途，按住上下拖动。大小卡片共用背景透明度。", 12, MUTED));
    final SeekBar cardTransparency = transparencyControl(form, "悬浮卡片背景透明度", false);
    form.addView(text("悬浮歌词背景透明度 · 固定 100%（全透明）", 14, INK));
    form.addView(text("卡片背景：0% 不透明 · 100% 全透明，仅影响背景。悬浮歌词背景固定全透明，不可修改。", 12, MUTED));
    form.addView(text("悬浮文字样式 · 三种样式分别保存", 14, INK));
    final OverlayStyleEditor[] textStyles = new OverlayStyleEditor[3];
    for (int i = 0; i < textStyles.length; i++) {
      textStyles[i] = new OverlayStyleEditor(this, i);
      textStyles[i].addTo(form);
    }
    form.addView(text("可预览字号、选择常用颜色或输入自定义色值。点击本页保存后生效，取消不会修改当前样式。", 12, MUTED));
    final CheckBox carCompat = new CheckBox(this);
    carCompat.setText("车机悬浮兼容模式");
    carCompat.setChecked(FloatingPlayer.compatibility(this));
    form.addView(carCompat);
    form.addView(
        text(
            FloatingPlayer.cs11Profile()
                ? "已识别 CS11：开启后忽略车机锁屏状态报告，仅允许悬浮显示，不解锁系统；熄屏仍隐藏。"
                : "Android 4.4 默认开启：允许在无安全锁屏的设备上显示。其他设备的安全锁屏仍隐藏，熄屏始终隐藏。",
            12,
            MUTED));
    if (!FloatingPlayer.allowed(this)) form.addView(text("尚未获得悬浮窗权限，开启并保存后将打开系统授权页面。", 12, MUTED));
    if (service != null && service.floatingError().length() > 0)
      form.addView(text(service.floatingError(), 12, ACCENT));
    form.addView(
        button(
            "悬浮窗权限设置",
            new Runnable() {
              public void run() {
                FloatingPlayer.openPermission(MainActivity.this);
              }
            }));
    form.addView(text("直接连接网易云，账号独立保存于本应用。\n音质受账号权限和车机解码能力限制，切歌后生效。", 12, MUTED));
    ScrollView scroll = new ScrollView(this);
    scroll.addView(form);
    final AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("播放设置")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create();
    dialog.show();
    dialog
        .getButton(AlertDialog.BUTTON_POSITIVE)
        .setOnClickListener(
            new View.OnClickListener() {
              public void onClick(View v) {
                android.content.SharedPreferences.Editor changes = prefs.edit();
                for (OverlayStyleEditor style : textStyles) style.write(changes);
                changes
                    .putString("quality", values[quality.getSelectedItemPosition()])
                    .putInt("fmCount", counts[fmCount.getSelectedItemPosition()])
                    .putBoolean("screen", screen.isChecked())
                    .putBoolean("bootStart", bootStart.isChecked())
                    .putBoolean("autoPlay", autoPlay.isChecked())
                    .putBoolean("floating", floating.isChecked() || largeFloating.isChecked())
                    .putBoolean("floatingLarge", largeFloating.isChecked())
                    .putBoolean("floatingLyrics", floatingLyrics.isChecked())
                    .putBoolean("overlayCarCompat", carCompat.isChecked())
                    .putInt(FloatingPlayer.transparencyKey(false), cardTransparency.getProgress())
                    .remove(FloatingPlayer.transparencyKey(true))
                    .remove("launcherLyrics")
                    .apply();
                if (service != null)
                  service.setFloatingEnabled(floating.isChecked() || largeFloating.isChecked());
                if (service != null) service.startupSettingsChanged();
                keepScreen();
                dialog.dismiss();
                account();
                if ((floating.isChecked()
                        || largeFloating.isChecked()
                        || floatingLyrics.isChecked())
                    && !FloatingPlayer.allowed(MainActivity.this))
                  FloatingPlayer.openPermission(MainActivity.this);
              }
            });
  }

  private SeekBar transparencyControl(LinearLayout form, final String name, boolean lyrics) {
    int value = FloatingPlayer.transparency(this, lyrics);
    final TextView label = text(name + " · " + value + "%", 14, INK);
    label.setPadding(0, dp(12), 0, 0);
    form.addView(label);
    SeekBar slider = (SeekBar) getLayoutInflater().inflate(R.layout.seek, null);
    slider.setMax(100);
    slider.setContentDescription(name);
    slider.setPadding(dp(10), 0, dp(10), 0);
    GradientDrawable thumb = bg(ACCENT);
    thumb.setShape(GradientDrawable.OVAL);
    thumb.setSize(dp(20), dp(20));
    slider.setThumb(thumb);
    slider.setThumbOffset(dp(10));
    android.graphics.drawable.ClipDrawable fill =
        new android.graphics.drawable.ClipDrawable(
            bg(ACCENT), Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL);
    android.graphics.drawable.LayerDrawable track =
        new android.graphics.drawable.LayerDrawable(
            new android.graphics.drawable.Drawable[] {bg(LINE_COLOR()), fill});
    track.setId(0, android.R.id.background);
    track.setId(1, android.R.id.progress);
    slider.setProgressDrawable(track);
    slider.setProgress(value);
    slider.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar bar, int progress, boolean user) {
            label.setText(name + " · " + progress + "%");
          }

          public void onStartTrackingTouch(SeekBar bar) {}

          public void onStopTrackingTouch(SeekBar bar) {}
        });
    form.addView(slider, new LinearLayout.LayoutParams(-1, dp(48)));
    return slider;
  }

  private void keepScreen() {
    if (Api.prefs(this).getBoolean("screen", false))
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }

  private void login() {
    if (qrDialog != null) qrDialog.dismiss();
    final int version = ++qrVersion;
    final LinearLayout box = vertical();
    box.setGravity(Gravity.CENTER);
    final TextView status = text("正在生成二维码…", 17, INK);
    status.setGravity(Gravity.CENTER);
    box.addView(status, new LinearLayout.LayoutParams(-1, dp(42)));
    final ImageView image = new ImageView(this);
    image.setBackgroundColor(Color.WHITE);
    box.addView(image, new LinearLayout.LayoutParams(dp(190), dp(190)));
    qrDialog =
        new AlertDialog.Builder(this)
            .setTitle("使用手机网易云音乐扫码")
            .setView(box)
            .setNegativeButton("关闭", null)
            .setNeutralButton("刷新二维码", null)
            .create();
    qrDialog.setOnDismissListener(
        new DialogInterface.OnDismissListener() {
          public void onDismiss(DialogInterface d) {
            qrVersion++;
            qrDialog = null;
          }
        });
    qrDialog.show();
    qrDialog
        .getButton(AlertDialog.BUTTON_NEUTRAL)
        .setOnClickListener(
            new View.OnClickListener() {
              public void onClick(View v) {
                login();
              }
            });
    Api.request(
        this,
        "POST",
        "/v1/login/qr",
        new Api.Callback() {
          public void done(JSONObject o, String error) {
            if (version != qrVersion) return;
            if (error != null) {
              status.setText(error);
              return;
            }
            try {
              String data = o.getString("image");
              byte[] bytes =
                  android.util.Base64.decode(
                      data.substring(data.indexOf(',') + 1), android.util.Base64.DEFAULT);
              image.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.length));
              pollQr(o.getString("id"), version, status);
            } catch (Exception e) {
              status.setText("二维码生成失败，请刷新");
            }
          }
        });
  }

  private void pollQr(final String id, final int version, final TextView status) {
    if (version != qrVersion || !visible) return;
    Api.request(
        this,
        "GET",
        "/v1/login/qr/check?id=" + Api.encode(id),
        new Api.Callback() {
          public void done(JSONObject o, String error) {
            if (version != qrVersion) return;
            if (error != null) {
              status.setText(error + "，请刷新二维码");
              return;
            }
            int code = o.optInt("status");
            status.setText(o.optString("message"));
            if (code == 803) {
              if (qrDialog != null) qrDialog.dismiss();
              toast("登录成功");
              playlists();
            } else if (code != 800)
              handler.postDelayed(
                  new Runnable() {
                    public void run() {
                      pollQr(id, version, status);
                    }
                  },
                  2500);
          }
        });
  }

  public void changed() {
    if (service == null) return;
    renderQueue();
    Track t = service.current();
    nowTitle.setText(t == null ? "云途音乐" : t.name);
    nowState.setText(t == null ? "选一首歌，开始旅途" : t.artist);
    String state =
        service.status()
            + (service.quality().length() > 0 ? "  ·  " + qualityName(service.quality()) : "");
    play.playing(service.isPlaying());
    if (largePlay != null) largePlay.playing(service.isPlaying());
    if (songTitle != null) songTitle.setText(t == null ? "让喜欢的音乐，一路相伴" : t.name);
    if (songArtist != null)
      songArtist.setText(
          t == null ? "云途音乐" : t.artist + (t.album.length() > 0 ? "  ·  " + t.album : ""));
    if (playerStatus != null) playerStatus.setText(t == null ? "歌单 / FM / 搜索" : state);
    if (t != null && !section.equals("当前播放")) nowState.setText(t.artist + " · " + service.status());
    if (mode != null) {
      mode.kind = service.mode();
      mode.tinted = false;
      mode.setContentDescription(
          service.mode().equals("repeat")
              ? "单曲循环"
              : service.mode().equals("shuffle") ? "乱序播放" : "顺序播放");
      mode.invalidate();
    }
    String id = t == null ? "" : t.id;
    if (!id.equals(displayedId)) {
      displayedId = id;
      if (t != null) {
        loadPresentation(t);
        loadLike(t);
      } else {
        presentationVersion++;
        coverUrl = "";
        lyricLines = new JSONArray();
        lyricPlain = "";
        lyricEmpty = "从歌单、FM 或搜索中\n选一首喜欢的歌";
        ArtworkLoader.load(miniArt, "", 160);
        if (albumArt != null) ArtworkLoader.load(albumArt, "", 480);
        renderLyrics();
      }
    } else if (t == null && lyricsView != null) lyricsView.message("从歌单、FM 或搜索中\n选一首喜欢的歌");
    updateLikeButton();
    updateProgress();
  }

  private void updateLikeButton() {
    boolean has = service != null && service.current() != null;
    if (likeButton != null) {
      likeButton.tinted = liked;
      likeButton.invalidate();
      likeButton.setContentDescription(liked ? "取消喜欢当前歌曲" : "喜欢当前歌曲");
      likeButton.setEnabled(has && !likeBusy);
      likeButton.setAlpha(has ? 1f : .4f);
    }
    if (saveButton != null) {
      saveButton.setEnabled(has);
      saveButton.setAlpha(has ? 1f : .4f);
    }
  }

  private void loadLike(final Track track) {
    final int version = ++likeVersion;
    liked = false;
    likeBusy = true;
    updateLikeButton();
    Api.request(
        this,
        "GET",
        "/v1/songs/" + track.id + "/like",
        new Api.Callback() {
          public void done(JSONObject o, String error) {
            if (version != likeVersion || !isCurrent(track.id)) return;
            likeBusy = false;
            if (error == null) liked = o.optBoolean("liked");
            updateLikeButton();
          }
        });
  }

  private boolean isCurrent(String id) {
    return service != null && service.current() != null && id.equals(service.current().id);
  }

  private void toggleLike() {
    if (service == null || service.current() == null || likeBusy) return;
    final Track track = service.current();
    final boolean desired = !liked;
    final int version = ++likeVersion;
    likeBusy = true;
    updateLikeButton();
    Api.request(
        this,
        "POST",
        "/v1/songs/" + track.id + "/like?value=" + desired,
        new Api.Callback() {
          public void done(JSONObject o, String error) {
            if (version == likeVersion && isCurrent(track.id)) {
              likeBusy = false;
              if (error == null) liked = o.optBoolean("liked");
              updateLikeButton();
            }
            if (error != null) toast(error);
          }
        });
  }

  private void choosePlaylist() {
    if (service == null || service.current() == null) {
      toast("请先选择歌曲");
      return;
    }
    final Track track = service.current();
    if (saveDialog != null) saveDialog.dismiss();
    final int version = ++pickerVersion;
    final Dialog dialog = new Dialog(this);
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
    saveDialog = dialog;
    LinearLayout box = vertical();
    box.setPadding(dp(22), dp(16), dp(22), dp(12));
    GradientDrawable panel = bg(0xff19191f);
    panel.setCornerRadius(dp(18));
    panel.setStroke(dp(1), 0xff303039);
    box.setBackground(panel);
    LinearLayout header = new LinearLayout(this);
    header.setGravity(Gravity.CENTER_VERTICAL);
    TextView heading = text("收藏到歌单", 23, INK);
    heading.setTypeface(null, Typeface.BOLD);
    header.addView(heading, new LinearLayout.LayoutParams(0, dp(44), 1));
    PlayerViews.Icon close =
        new PlayerViews.Icon(
            this,
            "close",
            "关闭收藏窗口",
            new Runnable() {
              public void run() {
                dialog.dismiss();
              }
            });
    close.bare = true;
    header.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
    box.addView(header);
    LinearLayout song = new LinearLayout(this);
    song.setGravity(Gravity.CENTER_VERTICAL);
    song.setPadding(0, dp(6), 0, dp(12));
    PlayerViews.Art art = new PlayerViews.Art(this, false);
    song.addView(art, new LinearLayout.LayoutParams(dp(44), dp(44)));
    ArtworkLoader.load(art, coverUrl, 160);
    LinearLayout info = vertical();
    info.setPadding(dp(12), 0, 0, 0);
    TextView name = text(track.name, 17, INK);
    name.setSingleLine(true);
    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
    info.addView(name);
    TextView artist = text(track.artist, 12, MUTED);
    artist.setSingleLine(true);
    info.addView(artist);
    song.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
    box.addView(song);
    TextView caption = text("选择一个歌单", 12, MUTED);
    box.addView(caption, new LinearLayout.LayoutParams(-1, dp(26)));
    ScrollView scroll = new ScrollView(this);
    scroll.setVerticalScrollBarEnabled(false);
    final LinearLayout choices = vertical();
    scroll.addView(choices);
    box.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    final TextView status = text("正在加载歌单…", 12, MUTED);
    status.setSingleLine(false);
    box.addView(status, new LinearLayout.LayoutParams(-1, dp(34)));
    dialog.setContentView(box);
    dialog.setCanceledOnTouchOutside(true);
    dialog.setOnDismissListener(
        new DialogInterface.OnDismissListener() {
          public void onDismiss(DialogInterface d) {
            pickerVersion++;
            saveDialog = null;
          }
        });
    Window window = dialog.getWindow();
    window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
    window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    WindowManager.LayoutParams attrs = window.getAttributes();
    attrs.dimAmount = .72f;
    window.setAttributes(attrs);
    dialog.show();
    android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
    getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
    window.setLayout(
        Math.min(dp(560), metrics.widthPixels - dp(64)),
        Math.min(dp(compact ? 350 : 470), metrics.heightPixels - dp(32)));
    window
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    loadSavePlaylists(track, 0, version, choices, status);
  }

  private void loadSavePlaylists(
      final Track track,
      final int offset,
      final int version,
      final LinearLayout choices,
      final TextView status) {
    Api.request(
        this,
        "GET",
        "/v1/playlists?editable=true&offset=" + offset,
        new Api.Callback() {
          public void done(JSONObject o, String error) {
            if (version != pickerVersion) return;
            if (error != null) {
              status.setText(error);
              return;
            }
            JSONArray items = o.optJSONArray("items");
            if (items != null)
              for (int i = 0; i < items.length(); i++) {
                final JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                LinearLayout choice = new LinearLayout(MainActivity.this);
                choice.setGravity(Gravity.CENTER_VERTICAL);
                choice.setPadding(dp(12), dp(8), dp(12), dp(8));
                choice.setBackground(bg(0xff24242c));
                PlayerViews.Art cover = new PlayerViews.Art(MainActivity.this, false);
                choice.addView(cover, new LinearLayout.LayoutParams(dp(42), dp(42)));
                ArtworkLoader.load(cover, item.optString("cover"), 120);
                LinearLayout info = vertical();
                info.setPadding(dp(12), 0, dp(8), 0);
                TextView name = text(item.optString("name"), 16, INK);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                info.addView(name);
                info.addView(text(item.optInt("count") + " 首歌曲", 12, MUTED));
                choice.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
                TextView plus = text("＋", 24, MUTED);
                choice.addView(plus, new LinearLayout.LayoutParams(dp(28), -1));
                choice.setFocusable(true);
                choice.setContentDescription("收藏到：" + item.optString("name"));
                choice.setOnClickListener(
                    new View.OnClickListener() {
                      public void onClick(View v) {
                        saveToPlaylist(track, item.optString("id"), version, choices, status);
                      }
                    });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(62));
                lp.bottomMargin = dp(8);
                choices.addView(choice, lp);
              }
            status.setText(choices.getChildCount() == 0 ? "还没有可编辑的歌单，请先在网易云创建" : "仅显示你创建的歌单");
            final int next = o.optInt("nextOffset", offset + (items == null ? 0 : items.length()));
            if (o.optBoolean("more") && next > offset) {
              final Button[] more = new Button[1];
              more[0] =
                  button(
                      "加载更多歌单",
                      new Runnable() {
                        public void run() {
                          choices.removeView(more[0]);
                          status.setText("正在加载…");
                          loadSavePlaylists(track, next, version, choices, status);
                        }
                      });
              choices.addView(more[0]);
            }
          }
        });
  }

  private void saveToPlaylist(
      final Track track,
      String playlistId,
      final int version,
      final LinearLayout choices,
      final TextView status) {
    if (version != pickerVersion) return;
    for (int i = 0; i < choices.getChildCount(); i++) choices.getChildAt(i).setEnabled(false);
    status.setText("正在收藏…");
    Api.request(
        this,
        "POST",
        "/v1/playlists/" + playlistId + "/tracks?songId=" + track.id,
        new Api.Callback() {
          public void done(JSONObject o, String error) {
            if (version == pickerVersion) {
              if (error == null) {
                if (saveDialog != null) saveDialog.dismiss();
              } else {
                status.setText(error);
                for (int i = 0; i < choices.getChildCount(); i++)
                  choices.getChildAt(i).setEnabled(true);
              }
            }
            if (error == null) toast("已收藏：" + track.name);
          }
        });
  }

  private String qualityName(String q) {
    return q.replace("standard", "标准")
        .replace("higher", "较高")
        .replace("exhigh", "极高")
        .replace("lossless", "无损");
  }

  private void updateProgress() {
    if (service == null) return;
    int p = service.position(), d = service.duration();
    int progress = d > 0 ? (int) ((long) p * 1000 / d) : 0;
    if (seek != null && !scrubbing) seek.setProgress(progress);
    bottomProgress.value(progress / 1000f);
    time.setText(clock(p) + " / " + clock(d));
    if (elapsed != null && !scrubbing) elapsed.setText(clock(p));
    if (total != null) total.setText(clock(d));
    if (lyricsView != null) lyricsView.position(p);
  }

  private String clock(int ms) {
    int s = Math.max(0, ms / 1000);
    return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
  }

  public boolean onKeyDown(int code, KeyEvent event) {
    String a = MediaButtonReceiver.action(code);
    if (a != null) {
      if (event.getRepeatCount() == 0)
        startService(new Intent(this, PlaybackService.class).setAction(a));
      return true;
    }
    return super.onKeyDown(code, event);
  }
}
