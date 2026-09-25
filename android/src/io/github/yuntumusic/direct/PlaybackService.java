package io.github.yuntumusic.direct;

import android.app.*;
import android.content.*;
import android.media.*;
import android.os.*;
import android.util.Log;
import java.util.*;
import org.json.*;

public final class PlaybackService extends Service
    implements AudioManager.OnAudioFocusChangeListener {
  public final class LocalBinder extends Binder {
    public PlaybackService get() {
      return PlaybackService.this;
    }
  }

  public interface Listener {
    void changed();
  }

  private final LocalBinder binder = new LocalBinder();
  private final Handler handler = new Handler();
  private AsyncPlayer player;
  private AudioManager audio;
  private RemoteControlClient remote;
  private ComponentName receiver;
  private Listener listener;
  private FloatingPlayer floatingPlayer;
  private StartupPlayback startup;
  private boolean uiVisible, stopped = true, buffering;
  private int publishedDuration = -1;
  private final Runnable mediaTick =
      new Runnable() {
        public void run() {
          if (!stopped && current() != null) publishPlaybackPosition();
          updateFloatingPlayer();
          handler.postDelayed(this, 1000);
        }
      };
  private final ArrayList<Track> queue = new ArrayList<Track>();
  private int index = -1, generation = 0;
  private boolean ready = false,
      wantsPlay = false,
      playing = false,
      resumeOnFocus = false,
      fm = false,
      hasFocus = false;
  private String pagePath = "";
  private int pageOffset = 0;
  private boolean pageMore = false, pageLoading = false, fillQueue = false, pageAdvance = false;
  private int queueGeneration = 0, queueRevision = 0, advanceGeneration = 0;
  private String pageError = "";
  private final HashSet<String> unavailableTracks = new HashSet<String>();
  private boolean pendingUnavailable = false;
  private int skippedFmBatches = 0;
  private String state = "尚未播放", actualQuality = "";
  private String mode = "order";
  private final Random random = new Random();
  private Runnable timeout, stallCheck;
  private long lastProgressAt;
  private int lastPosition;
  private final BroadcastReceiver noisy =
      new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
          pause();
        }
      };

  public IBinder onBind(Intent intent) {
    return binder;
  }

  public void setListener(Listener l) {
    listener = l;
  }

  public void setUiVisible(boolean visible) {
    uiVisible = visible;
    if (visible && floatingPlayer != null) floatingPlayer.retry();
    updateFloatingPlayer();
  }

  public void setFloatingEnabled(boolean enabled) {
    Api.prefs(this).edit().putBoolean("floating", enabled).apply();
    if (floatingPlayer != null) floatingPlayer.retry();
    updateFloatingPlayer();
  }

  public String floatingError() {
    return floatingPlayer == null ? "" : floatingPlayer.error();
  }

  public void updateFloatingPlayer() {
    if (floatingPlayer != null) floatingPlayer.update(uiVisible, stopped);
  }

  public Track current() {
    return index >= 0 && index < queue.size() ? queue.get(index) : null;
  }

  public boolean isPlaying() {
    return playing;
  }

  public String status() {
    return state;
  }

  public String quality() {
    return actualQuality;
  }

  public String mode() {
    return mode;
  }

  public ArrayList<Track> tracks() {
    return new ArrayList<Track>(queue);
  }

  public int queueRevision() {
    return queueRevision;
  }

  public boolean queueLoading() {
    return pageLoading;
  }

  public boolean queueHasMore() {
    return !fm && pageMore;
  }

  public String queueError() {
    return pageError;
  }

  public String queueStatus() {
    if (pageLoading) return "正在加载其余歌曲…";
    if (pageError.length() > 0) return "加载未完成：" + pageError;
    return pageMore ? "还有歌曲待加载" : fm ? "私人 FM" : "已全部加载";
  }

  public void loadRemainingQueue() {
    if (fm || queue.isEmpty() || !pageMore || pagePath.length() == 0) return;
    fillQueue = true;
    requestPage(false);
  }

  private boolean playlistQueue() {
    return pagePath.startsWith("/v1/playlists/");
  }

  private void invalidatePages() {
    queueGeneration++;
    pageLoading = false;
    pageAdvance = false;
    pageError = "";
  }

  public int position() {
    return ready && player != null ? player.position() : 0;
  }

  public int duration() {
    int value = player == null ? 0 : player.duration();
    return value > 0 ? value : current() == null ? 0 : current().duration;
  }

  public void seek(int ms) {
    if (ready && player != null) {
      lastProgressAt = SystemClock.elapsedRealtime();
      player.seek(ms);
    }
  }

  public void cycleMode() {
    mode = mode.equals("order") ? "shuffle" : mode.equals("shuffle") ? "repeat" : "order";
    Api.prefs(this).edit().putString("mode", mode).apply();
    changed();
  }

  public void onCreate() {
    super.onCreate();
    audio = (AudioManager) getSystemService(AUDIO_SERVICE);
    mode = Api.prefs(this).getString("mode", "order");
    receiver = new ComponentName(this, MediaButtonReceiver.class);
    Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(receiver);
    remote =
        new RemoteControlClient(
            PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT));
    remote.setTransportControlFlags(
        RemoteControlClient.FLAG_KEY_MEDIA_PLAY
            | RemoteControlClient.FLAG_KEY_MEDIA_PAUSE
            | RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE
            | RemoteControlClient.FLAG_KEY_MEDIA_NEXT
            | RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS
            | RemoteControlClient.FLAG_KEY_MEDIA_STOP
            | RemoteControlClient.FLAG_KEY_MEDIA_POSITION_UPDATE);
    remote.setOnGetPlaybackPositionListener(
        new RemoteControlClient.OnGetPlaybackPositionListener() {
          public long onGetPlaybackPosition() {
            return position();
          }
        });
    remote.setPlaybackPositionUpdateListener(
        new RemoteControlClient.OnPlaybackPositionUpdateListener() {
          public void onPlaybackPositionUpdate(long ms) {
            seek((int) Math.max(0, Math.min(ms, duration())));
          }
        });
    floatingPlayer = new FloatingPlayer(this);
    startup = new StartupPlayback(this);
    handler.post(mediaTick);
    registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    restore();
    if (playlistQueue())
      handler.post(
          new Runnable() {
            public void run() {
              loadRemainingQueue();
            }
          });
  }

  public int onStartCommand(Intent intent, int flags, int id) {
    String a = intent == null ? null : intent.getAction();
    if ("boot_start".equals(a)) {
      // A foreground-service launch must publish a notification even with an empty queue.
      if (!wantsPlay && !playing) startupStatus("云途已启动，点击通知打开应用");
      else changed();
      if (Api.prefs(this).getBoolean("bootStart", false)) startup.begin();
      else if (!wantsPlay && !playing) stopPlayback();
    } else if ("toggle".equals(a)) toggle();
    else if ("play".equals(a)) play();
    else if ("pause".equals(a)) pause();
    else if ("next".equals(a)) next(true);
    else if ("previous".equals(a)) previous();
    else if ("stop".equals(a)) stopPlayback();
    return START_NOT_STICKY;
  }

  public void select(int selected) {
    startup.cancel();
    if (selected >= 0 && selected < queue.size()) {
      resetUnavailable();
      index = selected;
      load();
    }
  }

  public void clearQueue() {
    stopPlayback();
    queue.clear();
    queueRevision++;
    index = -1;
    fm = false;
    pagePath = "";
    pageMore = false;
    deleteFile("queue.json");
    if (listener != null) listener.changed();
  }

  public void playQueue(
      ArrayList<Track> items, int selected, boolean radio, String path, int offset, boolean more) {
    startup.cancel();
    if (items.isEmpty()) return;
    invalidatePages();
    resetUnavailable();
    pagePath = path;
    pageOffset = offset;
    pageMore = more;
    fillQueue = !radio && playlistQueue();
    queue.clear();
    queue.addAll(items);
    queueRevision++;
    index = Math.max(0, Math.min(selected, queue.size() - 1));
    fm = radio;
    load();
    if (fillQueue) loadRemainingQueue();
  }

  public void toggle() {
    startup.cancel();
    if (wantsPlay || playing) pause();
    else play();
  }

  public void play() {
    startup.cancel();
    if (current() == null) {
      state = "请先选择歌曲";
      changed();
      return;
    }
    resumeOnFocus = false;
    wantsPlay = true;
    if (pendingUnavailable) {
      advanceUnavailable();
      return;
    }
    resetUnavailable();
    if (ready) startReady();
    else load();
    if (playlistQueue()) loadRemainingQueue();
  }

  public void pause() {
    startup.cancel();
    resumeOnFocus = false;
    wantsPlay = false;
    playing = false;
    if (ready)
      try {
        player.pause();
      } catch (Exception ignored) {
      }
    releaseFocus();
    state = "已暂停";
    changed();
  }

  public void stopPlayback() {
    startup.cancel();
    generation++;
    invalidatePages();
    resetUnavailable();
    wantsPlay = false;
    playing = false;
    resumeOnFocus = false;
    stopped = true;
    buffering = false;
    releasePlayer();
    releaseFocus();
    state = "已停止";
    remote.setPlaybackState(RemoteControlClient.PLAYSTATE_STOPPED, 0, 0f);
    updateFloatingPlayer();
    stopForeground(true);
    if (listener != null) listener.changed();
    stopSelf();
  }

  public void next(boolean user) {
    if (user) startup.cancel();
    if (queue.isEmpty()) return;
    if (user) resetUnavailable();
    if (!user && mode.equals("repeat")) {
      load();
      return;
    }
    if (mode.equals("shuffle") && queue.size() > 1) {
      int old = index;
      while (index == old) index = random.nextInt(queue.size());
    } else {
      if (!fm && pageMore && index >= queue.size() - 1) {
        requestPage(true);
        return;
      }
      if (fm && index >= queue.size() - 1) {
        loadFm();
        return;
      }
      index = (index + 1) % queue.size();
    }
    load();
  }

  public void previous() {
    startup.cancel();
    if (queue.isEmpty()) return;
    resetUnavailable();
    if (position() > 3000) {
      seek(0);
      return;
    }
    index = (index - 1 + queue.size()) % queue.size();
    load();
  }

  private void requestPage(boolean advance) {
    if (fm || !pageMore || pagePath.length() == 0) return;
    if (advance) {
      advanceGeneration = ++generation;
      pageAdvance = true;
      releasePlayer();
      playing = false;
      wantsPlay = true;
      state = "正在续载歌单…";
    }
    if (pageLoading) {
      changed();
      return;
    }
    final int ticket = queueGeneration;
    final int offset = pageOffset;
    pageLoading = true;
    pageError = "";
    changed();
    Api.request(
        this,
        "GET",
        pagePath + (pagePath.indexOf('?') >= 0 ? "&" : "?") + "offset=" + offset,
        new Api.Callback() {
          public void done(JSONObject o, String error) {
            // Queue metadata survives track changes, but never crosses a replaced/cleared queue.
            if (ticket != queueGeneration) return;
            pageLoading = false;
            if (error != null) {
              pageFailed(error);
              return;
            }
            try {
              ArrayList<Track> add = Track.parse(o.optJSONArray("items"));
              int next = o.optInt("nextOffset", offset + add.size());
              boolean more = o.optBoolean("more");
              if (more && (add.isEmpty() || next <= offset)) {
                pageFailed("歌单分页未前进，请重试");
                return;
              }
              int before = queue.size();
              HashSet<String> seen = new HashSet<String>();
              for (Track item : queue) seen.add(item.id);
              for (Track item : add) if (item.id.length() > 0 && seen.add(item.id)) queue.add(item);
              pageMore = more;
              pageOffset = next;
              pageError = "";
              if (queue.size() != before) queueRevision++;
              boolean resumeNext = pageAdvance && advanceGeneration == generation;
              if (!resumeNext) pageAdvance = false;
              if (resumeNext && (queue.size() > before || !pageMore)) {
                pageAdvance = false;
                if (pendingUnavailable) {
                  if (wantsPlay) advanceUnavailable();
                } else {
                  index = (index + 1) % queue.size();
                  loadContinuation();
                }
              }
              save();
              changed();
              if (pageMore && (fillQueue || pageAdvance))
                handler.post(
                    new Runnable() {
                      public void run() {
                        if (ticket == queueGeneration) requestPage(false);
                      }
                    });
            } catch (Exception e) {
              pageFailed("歌单续载失败，请重试");
            }
          }
        });
  }

  private void pageFailed(String error) {
    pageError = error;
    boolean waiting = pageAdvance && advanceGeneration == generation;
    pageAdvance = false;
    if (waiting) failed(error);
    else changed(); // A background metadata failure must not interrupt current audio.
  }

  private void loadFm() {
    final int ticket = ++generation;
    releasePlayer();
    playing = false;
    wantsPlay = true;
    state = "正在获取下一组私人 FM";
    changed();
    Api.request(
        this,
        "GET",
        "/v1/fm?limit=" + Api.fmCount(this),
        new Api.Callback() {
          public void done(JSONObject o, String error) {
            if (ticket != generation) return;
            if (error != null) {
              failed(error);
              return;
            }
            try {
              ArrayList<Track> more = Track.parse(o.optJSONArray("items"));
              if (more.isEmpty()) {
                failed("私人 FM 暂无歌曲");
                return;
              }
              queue.addAll(more);
              queueRevision++;
              index++;
              if (queue.size() > 100) {
                int trim = queue.size() - 100;
                queue.subList(0, trim).clear();
                index -= trim;
              }
              loadContinuation();
            } catch (Exception e) {
              failed("私人 FM 响应异常");
            }
          }
        });
  }

  private void loadContinuation() {
    // A response must retain both user pause and pending transient-focus resume intent.
    boolean autoplay = wantsPlay;
    boolean resume = resumeOnFocus;
    load();
    if (!autoplay) {
      wantsPlay = false;
      resumeOnFocus = resume;
      state = resume ? "临时让出音频" : "已暂停";
      changed();
    }
  }

  private void load() {
    if (current() == null) return;
    final int ticket = ++generation;
    releasePlayer();
    stopped = false;
    buffering = false;
    playbackLog("load");
    playing = false;
    wantsPlay = true;
    resumeOnFocus = false;
    actualQuality = "";
    pendingUnavailable = false;
    state = "正在获取音源…";
    startService(new Intent(this, PlaybackService.class));
    changed();
    save();
    final String quality = Api.prefs(this).getString("quality", "exhigh");
    Api.request(
        this,
        "GET",
        "/v1/songs/" + current().id + "/url?quality=" + quality,
        new Api.Callback() {
          public void done(JSONObject o, String error) {
            if (ticket != generation) return;
            if (error != null) {
              if (o != null && "NO_PLAYABLE_SOURCE".equals(o.optString("code")))
                sourceUnavailable();
              else failed(error);
              return;
            }
            try {
              final AsyncPlayer p =
                  new AsyncPlayer(
                      PlaybackService.this,
                      new AsyncPlayer.Listener() {
                        public void prepared() {
                          if (ticket != generation) return;
                          cancelTimeout();
                          ready = true;
                          watchProgress(ticket);
                          if (wantsPlay) startReady();
                          else {
                            state = "已暂停";
                            changed();
                          }
                        }

                        public void started() {
                          if (ticket != generation || !wantsPlay) return;
                          resetUnavailable();
                          playing = true;
                          buffering = false;
                          lastProgressAt = SystemClock.elapsedRealtime();
                          state = "正在播放";
                          changed();
                        }

                        public void completed() {
                          if (ticket == generation && wantsPlay) next(false);
                        }

                        public void buffering(boolean active) {
                          if (ticket != generation || !wantsPlay) return;
                          buffering = active;
                          state = active ? "正在缓冲…" : playing ? "正在播放" : "正在启动播放…";
                          playbackLog(active ? "buffering_start" : "buffering_end");
                          changed();
                        }

                        public void error(String message) {
                          if (ticket == generation) failed(message);
                        }
                      });
              player = p;
              actualQuality = o.optString("quality") + (o.optBoolean("trial") ? " · 试听" : "");
              state = wantsPlay ? "正在缓冲…" : "已暂停";
              changed();
              p.prepare(o.getString("url"));
              timeout =
                  new Runnable() {
                    public void run() {
                      if (ticket == generation && !ready) {
                        playbackLog("prepare_timeout");
                        failed("音频缓冲超时，请检查网络或换一首");
                      }
                    }
                  };
              handler.postDelayed(timeout, 30000);
            } catch (Exception e) {
              failed(e instanceof IllegalStateException ? e.getMessage() : "无法打开音频，请检查网络或切换标准音质");
            }
          }
        });
  }

  private void resetUnavailable() {
    unavailableTracks.clear();
    pendingUnavailable = false;
    skippedFmBatches = 0;
  }

  private void sourceUnavailable() {
    unavailableTracks.add(current().id);
    pendingUnavailable = true;
    if (!wantsPlay) {
      state = resumeOnFocus ? "临时让出音频" : "已暂停";
      changed();
      return;
    }
    state = "当前歌曲无音源，正在跳过…";
    changed();
    final int ticket = generation;
    handler.postDelayed(
        new Runnable() {
          public void run() {
            if (ticket == generation && wantsPlay && pendingUnavailable) advanceUnavailable();
          }
        },
        750);
  }

  private void advanceUnavailable() {
    if (!wantsPlay || queue.isEmpty()) return;
    if (mode.equals("shuffle")) {
      ArrayList<Integer> candidates = new ArrayList<Integer>();
      for (int i = 0; i < queue.size(); i++)
        if (!unavailableTracks.contains(queue.get(i).id)) candidates.add(i);
      if (!candidates.isEmpty()) {
        index = candidates.get(random.nextInt(candidates.size()));
        load();
        return;
      }
      if (!fm && pageMore) {
        requestPage(true);
        return;
      }
    } else {
      for (int step = 1; step <= queue.size(); step++) {
        int at = index + step;
        if (at >= queue.size()) {
          if (!fm && pageMore) {
            requestPage(true);
            return;
          }
          if (fm && skippedFmBatches == 0 && unavailableTracks.size() < queue.size()) {
            skippedFmBatches++;
            loadFm();
            return;
          }
          at %= queue.size();
        }
        if (!unavailableTracks.contains(queue.get(at).id)) {
          index = at;
          load();
          return;
        }
      }
    }
    pendingUnavailable = false;
    failed("当前队列已尝试的歌曲均无可用音源，已停止播放");
  }

  private boolean acquireFocus() {
    if (!hasFocus)
      hasFocus =
          audio.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
              == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    if (hasFocus) {
      audio.registerMediaButtonEventReceiver(receiver);
      audio.registerRemoteControlClient(remote);
    }
    return hasFocus;
  }

  private void releaseFocus() {
    if (hasFocus) {
      audio.abandonAudioFocus(this);
      hasFocus = false;
    }
  }

  private void startReady() {
    if (!acquireFocus()) {
      wantsPlay = false;
      state = "其他音源正在使用音频，请稍后播放";
      changed();
      return;
    }
    lastProgressAt = SystemClock.elapsedRealtime();
    state = "正在启动播放…";
    player.start();
    changed();
  }

  private void watchProgress(final int ticket) {
    lastPosition = position();
    lastProgressAt = SystemClock.elapsedRealtime();
    stallCheck =
        new Runnable() {
          public void run() {
            if (ticket != generation || player == null) return;
            int now = position();
            long time = SystemClock.elapsedRealtime();
            if (!wantsPlay || now != lastPosition) lastProgressAt = time;
            lastPosition = now;
            if (wantsPlay && time - lastProgressAt >= 30000) {
              playbackLog("playback_stall_timeout");
              failed("音频长时间没有进展，请重试或切换下一首");
              return;
            }
            handler.postDelayed(this, 1000);
          }
        };
    handler.postDelayed(stallCheck, 1000);
  }

  private void playbackLog(String event) {
    Log.i(
        "YuntuPlayback",
        event
            + " song_id="
            + (current() == null ? "none" : current().id)
            + " position_ms="
            + position()
            + " generation="
            + generation);
  }

  private void failed(String message) {
    generation++;
    releasePlayer();
    playing = false;
    wantsPlay = false;
    releaseFocus();
    pendingUnavailable = false;
    resumeOnFocus = false;
    state = message;
    changed();
  }

  private void cancelTimeout() {
    if (timeout != null) {
      handler.removeCallbacks(timeout);
      timeout = null;
    }
  }

  private void releasePlayer() {
    cancelTimeout();
    if (stallCheck != null) handler.removeCallbacks(stallCheck);
    stallCheck = null;
    ready = false;
    AsyncPlayer old = player;
    player = null;
    if (old != null) old.close();
  }

  public void onAudioFocusChange(int change) {
    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
      startup.cancel();
    if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
      if (player != null && ready) player.volume(.2f);
    } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
      boolean resume = wantsPlay;
      wantsPlay = false;
      playing = false;
      if (ready)
        try {
          player.pause();
        } catch (Exception ignored) {
        }
      resumeOnFocus = resume;
      state = "临时让出音频";
      changed();
    } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
      hasFocus = false;
      pause();
    } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
      hasFocus = true;
      if (player != null && ready) player.volume(1f);
      if (resumeOnFocus) {
        resumeOnFocus = false;
        wantsPlay = true;
        if (pendingUnavailable) advanceUnavailable();
        else if (ready) startReady();
      }
    }
  }

  private PendingIntent action(String a, int id) {
    return PendingIntent.getService(
        this,
        id,
        new Intent(this, PlaybackService.class).setAction(a),
        PendingIntent.FLAG_UPDATE_CURRENT);
  }

  public void startAfterLaunch() {
    startup.begin();
  }

  public void startupSettingsChanged() {
    startup.settingsChanged();
  }

  void startupStatus(String message) {
    state = message;
    Notification n =
        new Notification.Builder(this)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("云途音乐")
            .setContentText(message)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    10,
                    new Intent(this, MainActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", action("stop", 4))
            .setOngoing(true)
            .build();
    startForeground(1, n);
    if (listener != null) listener.changed();
  }

  private void publishPlaybackPosition() {
    boolean advancing = playing && wantsPlay && !buffering;
    int playbackState =
        advancing
            ? RemoteControlClient.PLAYSTATE_PLAYING
            : wantsPlay
                ? RemoteControlClient.PLAYSTATE_BUFFERING
                : RemoteControlClient.PLAYSTATE_PAUSED;
    remote.setPlaybackState(playbackState, Math.max(0, position()), advancing ? 1f : 0f);
    int length = duration();
    if (length != publishedDuration) {
      remote
          .editMetadata(false)
          .putLong(MediaMetadataRetriever.METADATA_KEY_DURATION, length)
          .apply();
      publishedDuration = length;
    }
  }

  private void changed() {
    Track t = current();
    if (t != null) {
      remote
          .editMetadata(true)
          .putString(MediaMetadataRetriever.METADATA_KEY_TITLE, t.name)
          .putString(MediaMetadataRetriever.METADATA_KEY_ARTIST, t.artist)
          .putString(MediaMetadataRetriever.METADATA_KEY_ALBUM, t.album)
          .putLong(MediaMetadataRetriever.METADATA_KEY_DURATION, duration())
          .apply();
      publishedDuration = duration();
      if (!stopped) publishPlaybackPosition();
      Notification n =
          new Notification.Builder(this)
              .setSmallIcon(android.R.drawable.ic_media_play)
              .setContentTitle(t.name)
              .setContentText(t.artist + " · " + state)
              .setContentIntent(
                  PendingIntent.getActivity(
                      this,
                      10,
                      new Intent(this, MainActivity.class),
                      PendingIntent.FLAG_UPDATE_CURRENT))
              .setOngoing(playing || wantsPlay)
              .addAction(android.R.drawable.ic_media_previous, "上一首", action("previous", 1))
              .addAction(
                  playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                  playing ? "暂停" : "播放",
                  action("toggle", 2))
              .addAction(android.R.drawable.ic_media_next, "下一首", action("next", 3))
              .build();
      startForeground(1, n);
    }
    updateFloatingPlayer();
    if (listener != null) listener.changed();
  }

  private void save() {
    try {
      JSONArray a = new JSONArray();
      for (Track t : queue) a.put(t.json());
      JSONObject o = new JSONObject();
      o.put("items", a);
      o.put("index", index);
      o.put("fm", fm);
      o.put("pagePath", pagePath);
      o.put("pageOffset", pageOffset);
      o.put("pageMore", pageMore);
      java.io.FileOutputStream f = openFileOutput("queue.json", MODE_PRIVATE);
      try {
        f.write(o.toString().getBytes("UTF-8"));
      } finally {
        f.close();
      }
    } catch (Exception ignored) {
    }
  }

  private void restore() {
    try {
      java.io.FileInputStream f = openFileInput("queue.json");
      java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
      try {
        byte[] buf = new byte[4096];
        int n;
        while ((n = f.read(buf)) > 0) {
          if (b.size() > 2 * 1024 * 1024) throw new java.io.IOException();
          b.write(buf, 0, n);
        }
      } finally {
        f.close();
      }
      JSONObject o = new JSONObject(b.toString("UTF-8"));
      queue.addAll(Track.parse(o.optJSONArray("items")));
      queueRevision++;
      index = o.optInt("index", 0);
      fm = o.optBoolean("fm");
      pagePath = o.optString("pagePath");
      pageOffset = o.optInt("pageOffset");
      pageMore = o.optBoolean("pageMore");
      state = "队列已恢复，点击播放";
    } catch (Exception ignored) {
    }
  }

  public void onDestroy() {
    if (startup != null) startup.cancel();
    generation++;
    invalidatePages();
    releasePlayer();
    releaseFocus();
    audio.unregisterRemoteControlClient(remote);
    audio.unregisterMediaButtonEventReceiver(receiver);
    unregisterReceiver(noisy);
    if (floatingPlayer != null) floatingPlayer.destroy();
    handler.removeCallbacksAndMessages(null);
    super.onDestroy();
  }
}
