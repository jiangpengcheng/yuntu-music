package io.github.yuntumusic.direct;

import android.content.*;
import android.view.KeyEvent;

public final class MediaButtonReceiver extends BroadcastReceiver {
  public void onReceive(Context c, Intent i) {
    if (!Intent.ACTION_MEDIA_BUTTON.equals(i.getAction())) return;
    KeyEvent k = (KeyEvent) i.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
    if (k == null || k.getAction() != KeyEvent.ACTION_DOWN || k.getRepeatCount() != 0) return;
    String action = action(k.getKeyCode());
    if (action != null) {
      c.startService(new Intent(c, PlaybackService.class).setAction(action));
      if (isOrderedBroadcast()) abortBroadcast();
    }
  }

  static String action(int code) {
    switch (code) {
      case KeyEvent.KEYCODE_MEDIA_NEXT:
        return "next";
      case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
        return "previous";
      case KeyEvent.KEYCODE_MEDIA_PLAY:
        return "play";
      case KeyEvent.KEYCODE_MEDIA_PAUSE:
        return "pause";
      case KeyEvent.KEYCODE_MEDIA_STOP:
        return "stop";
      case KeyEvent.KEYCODE_HEADSETHOOK:
      case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
        return "toggle";
      default:
        return null;
    }
  }
}
