package io.github.yuntumusic.direct;

import android.app.*;
import android.content.*;
import android.os.*;
import java.util.*;
import org.json.*;

/** Opt-in startup policy and actual service integration; fixture audio only. */
public final class StartupTest extends MediaOutputTest {
  boolean online;
  String mode = "";
  StartupPlayback policy;
  final StartupPlayback.Network network =
      new StartupPlayback.Network() {
        public boolean connected() {
          return online;
        }
      };

  public static class ProbeContext extends ContextWrapper {
    int starts;
    Intent last;

    public ProbeContext(Context c) {
      super(c);
    }

    public ComponentName startService(Intent i) {
      starts++;
      last = i;
      return i.getComponent();
    }

    public ComponentName startForegroundService(Intent i) {
      return startService(i);
    }
  }

  public void onCreate(Bundle b) {
    super.onCreate(b);
    mode = b == null ? "" : b.getString("mode", "");
  }

  void fresh(final boolean connected, final boolean enabled) {
    ui(
        new Work() {
          public void run() throws Exception {
            service.pause();
            ((StartupPlayback) get(service, "startup")).cancel();
            Api.prefs(context).edit().putBoolean("autoPlay", enabled).apply();
            online = connected;
            policy = new StartupPlayback(service, network);
            set(service, "startup", policy);
          }
        });
  }

  void begin() {
    ui(
        new Work() {
          public void run() {
            policy.begin();
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
          .putBoolean("bootStart", false)
          .putBoolean("autoPlay", false)
          .putBoolean("floating", false)
          .putBoolean("floatingLarge", false)
          .putBoolean("floatingLyrics", false)
          .commit();
      final ProbeContext probe = new ProbeContext(context);
      final BootReceiver receiver = new BootReceiver();
      receiver.onReceive(probe, new Intent(Intent.ACTION_BOOT_COMPLETED));
      check(
          "boot disabled does not start service",
          new Check() {
            public boolean ok() {
              return probe.starts == 0;
            }
          });
      Api.prefs(context).edit().putBoolean("bootStart", true).commit();
      receiver.onReceive(probe, new Intent("unrelated.action"));
      check(
          "unrelated broadcast is ignored",
          new Check() {
            public boolean ok() {
              return probe.starts == 0;
            }
          });
      receiver.onReceive(probe, new Intent(Intent.ACTION_BOOT_COMPLETED));
      check(
          "boot starts explicit playback service without opening Activity",
          new Check() {
            public boolean ok() {
              return probe.starts == 1
                  && probe
                      .last
                      .getComponent()
                      .getClassName()
                      .equals(PlaybackService.class.getName())
                  && "boot_start".equals(probe.last.getAction());
            }
          });
      Api.prefs(context).edit().putBoolean("bootStart", false).commit();
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
      for (int i = 1; i <= 2; i++)
        tracks.add(
            new Track(
                new JSONObject()
                    .put("id", "" + i)
                    .put("name", "启动播放测试 " + i)
                    .put("artist", "云途本地联调")
                    .put("duration", 60000)));
      ui(
          new Work() {
            public void run() {
              service.playQueue(tracks, 0, false, "", 0, false);
            }
          });
      check(
          "fixture playing",
          new Check() {
            public boolean ok() {
              return service.isPlaying();
            }
          });
      fresh(true, false);
      begin();
      Thread.sleep(200);
      check(
          "autoplay disabled preserves pause",
          new Check() {
            public boolean ok() {
              return !service.isPlaying();
            }
          });
      fresh(true, true);
      begin();
      check(
          "enabled online startup plays restored current song",
          new Check() {
            public boolean ok() {
              return service.isPlaying() && "1".equals(service.current().id);
            }
          });
      ui(
          new Work() {
            public void run() {
              service.pause();
              service.startAfterLaunch();
            }
          });
      Thread.sleep(200);
      check(
          "reopening app does not override manual pause",
          new Check() {
            public boolean ok() {
              return !service.isPlaying();
            }
          });
      fresh(false, true);
      begin();
      check(
          "offline startup waits without starting audio",
          new Check() {
            public boolean ok() throws Exception {
              return !service.isPlaying()
                  && (Boolean) get(policy, "pending")
                  && service.status().contains("等待网络");
            }
          });
      online = true;
      check(
          "network restoration starts pending playback",
          new Check() {
            public boolean ok() {
              return service.isPlaying();
            }
          });
      fresh(false, true);
      begin();
      ui(
          new Work() {
            public void run() {
              service.pause();
              online = true;
            }
          });
      Thread.sleep(1200);
      check(
          "pause cancels delayed autoplay",
          new Check() {
            public boolean ok() throws Exception {
              return !service.isPlaying() && !(Boolean) get(policy, "pending");
            }
          });
      fresh(false, true);
      begin();
      ui(
          new Work() {
            public void run() {
              service.stopPlayback();
              online = true;
            }
          });
      Thread.sleep(1200);
      check(
          "stop cancels delayed autoplay",
          new Check() {
            public boolean ok() throws Exception {
              return !service.isPlaying() && !(Boolean) get(policy, "pending");
            }
          });
      fresh(false, true);
      begin();
      ui(
          new Work() {
            public void run() {
              Api.prefs(context).edit().putBoolean("autoPlay", false).apply();
              service.startupSettingsChanged();
              online = true;
            }
          });
      Thread.sleep(1200);
      check(
          "disabling preference cancels pending autoplay",
          new Check() {
            public boolean ok() {
              return !service.isPlaying() && service.status().contains("关闭");
            }
          });
      fresh(false, true);
      begin();
      ui(
          new Work() {
            public void run() throws Exception {
              set(policy, "deadline", SystemClock.elapsedRealtime() - 1);
            }
          });
      check(
          "network deadline stops waiting",
          new Check() {
            public boolean ok() throws Exception {
              return !(Boolean) get(policy, "pending") && service.status().contains("手动播放");
            }
          });
      online = true;
      Thread.sleep(1100);
      check(
          "network after deadline does not resume",
          new Check() {
            public boolean ok() {
              return !service.isPlaying();
            }
          });
      fresh(false, true);
      begin();
      ui(
          new Work() {
            public void run() {
              service.select(1);
              online = true;
            }
          });
      check(
          "manual selection wins over pending restored song",
          new Check() {
            public boolean ok() throws Exception {
              return service.isPlaying()
                  && "2".equals(service.current().id)
                  && !(Boolean) get(policy, "pending");
            }
          });
      ui(
          new Work() {
            public void run() {
              service.clearQueue();
            }
          });
      fresh(true, true);
      begin();
      Thread.sleep(100);
      check(
          "empty queue waits for user selection",
          new Check() {
            public boolean ok() throws Exception {
              return service.current() == null
                  && !service.isPlaying()
                  && !(Boolean) get(policy, "pending");
            }
          });
      ui(
          new Work() {
            public void run() {
              service.playQueue(tracks, 0, false, "", 0, false);
            }
          });
      check(
          "queue restored for boot-only test",
          new Check() {
            public boolean ok() {
              return service.isPlaying();
            }
          });
      fresh(true, false);
      ui(
          new Work() {
            public void run() {
              Api.prefs(context).edit().putBoolean("bootStart", true).apply();
              service.onStartCommand(new Intent("boot_start"), 0, 1);
            }
          });
      check(
          "boot-only setting keeps audio paused",
          new Check() {
            public boolean ok() {
              return !service.isPlaying() && service.status().contains("已启动");
            }
          });
      fresh(true, true);
      ui(
          new Work() {
            public void run() {
              service.onStartCommand(new Intent("boot_start"), 0, 2);
            }
          });
      check(
          "boot plus autoplay starts audio",
          new Check() {
            public boolean ok() {
              return service.isPlaying();
            }
          });
      ui(
          new Work() {
            public void run() {
              service.pause();
              service.onStartCommand(new Intent("boot_start"), 0, 3);
            }
          });
      Thread.sleep(200);
      check(
          "duplicate boot never overrides manual pause",
          new Check() {
            public boolean ok() {
              return !service.isPlaying();
            }
          });
      fresh(true, true);
      Api.prefs(context).edit().putString("token", "").commit();
      begin();
      check(
          "native autoplay needs no gateway credentials",
          new Check() {
            public boolean ok() throws Exception {
              return service.isPlaying() && !(Boolean) get(policy, "pending");
            }
          });
      Api.prefs(context)
          .edit()
          .putString("token", "local-emulator-test-token-00000001")
          .putBoolean("bootStart", true)
          .putBoolean("autoPlay", true)
          .commit();
      ui(
          new Work() {
            public void run() {
              service.stopPlayback();
            }
          });
      result.putString("stream", "\nPASS " + passed.size() + " startup checks\n" + passed + "\n");
      finish(Activity.RESULT_OK, result);
    } catch (Throwable t) {
      result.putString("stream", "\nFAIL after " + passed.size() + ": " + t + "\n" + passed + "\n");
      finish(Activity.RESULT_CANCELED, result);
    }
  }
}
