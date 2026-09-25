package io.github.yuntumusic.direct;

import android.content.*;
import android.os.Build;
import android.util.Log;

/** Opt-in, device-unlocked boot only; never launches an Activity over navigation. */
public final class BootReceiver extends BroadcastReceiver {
  public void onReceive(Context context, Intent intent) {
    if (intent == null
        || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
        || !Api.prefs(context).getBoolean("bootStart", false)) return;
    Intent service = new Intent(context, PlaybackService.class).setAction("boot_start");
    try {
      if (Build.VERSION.SDK_INT >= 26)
        Context.class.getMethod("startForegroundService", Intent.class).invoke(context, service);
      else context.startService(service);
      Log.i("YuntuStartup", "boot_service_requested");
    } catch (Exception e) {
      Log.w("YuntuStartup", "boot_start_denied type=" + e.getClass().getSimpleName());
    }
  }
}
