package io.github.yuntumusic.direct;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.*;
import android.util.Log;

/** One automatic attempt per service lifetime, bounded waiting, manual actions always win. */
final class StartupPlayback {
  interface Network {
    boolean connected();
  }

  private final PlaybackService service;
  private final Handler handler = new Handler();
  private final Network network;
  private boolean handled, pending;
  private long deadline;
  private final Runnable attempt =
      new Runnable() {
        public void run() {
          if (!pending) return;
          if (!Api.prefs(service).getBoolean("autoPlay", false) || service.current() == null) {
            cancel();
            service.startupStatus("自动播放已取消");
            return;
          }
          if (network.connected()) {
            pending = false;
            Log.i("YuntuStartup", "auto_play_begin");
            service.play();
          } else if (SystemClock.elapsedRealtime() >= deadline) {
            cancel();
            service.startupStatus("网络尚未连接，请联网后手动播放");
            Log.i("YuntuStartup", "network_wait_expired");
          } else handler.postDelayed(this, 1000);
        }
      };

  StartupPlayback(final PlaybackService service) {
    this(
        service,
        new Network() {
          public boolean connected() {
            try {
              ConnectivityManager manager =
                  (ConnectivityManager) service.getSystemService(Context.CONNECTIVITY_SERVICE);
              NetworkInfo info = manager.getActiveNetworkInfo();
              return info != null && info.isConnected();
            } catch (RuntimeException e) {
              return false;
            }
          }
        });
  }

  StartupPlayback(PlaybackService service, Network network) {
    this.service = service;
    this.network = network;
  }

  void begin() {
    if (handled) return;
    handled = true;
    if (!Api.prefs(service).getBoolean("autoPlay", false)) return;
    if (service.current() == null) {
      service.startupStatus("请先选择歌曲，下一次启动时自动播放");
      return;
    }
    pending = true;
    deadline = SystemClock.elapsedRealtime() + 60000;
    service.startService(new android.content.Intent(service, PlaybackService.class));
    service.startupStatus("正在等待网络，准备自动播放…");
    handler.post(attempt);
  }

  void cancel() {
    handled = true;
    pending = false;
    handler.removeCallbacks(attempt);
  }

  void settingsChanged() {
    if (pending && !Api.prefs(service).getBoolean("autoPlay", false)) {
      cancel();
      service.startupStatus("自动播放已关闭");
    }
  }
}
