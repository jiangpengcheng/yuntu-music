package io.github.yuntumusic.direct;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Isolated fixture only; callback simulation is not a CS11 lifecycle observation. */
public final class OverlayCompatTest extends MediaOutputTest {
  void require(String name, boolean value) {
    if (!value) throw new AssertionError(name);
    passed.add(name);
  }

  boolean flag() throws Exception {
    WindowManager.LayoutParams p = (WindowManager.LayoutParams) get(floating(), "params");
    return (p.flags & WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED) != 0;
  }

  void mode(final boolean lyrics, final boolean compat) {
    ui(
        new Work() {
          public void run() {
            Api.prefs(context)
                .edit()
                .putBoolean("floatingLyrics", lyrics)
                .putBoolean("overlayCarCompat", compat)
                .commit();
            service.setFloatingEnabled(!lyrics);
          }
        });
  }

  public void onStart() {
    Bundle result = new Bundle();
    try {
      context = getTargetContext();
      FixtureTransport.install(context);
      context.deleteFile("queue.json");
      context.deleteFile("overlay-diagnostics.jsonl");
      context.deleteFile("overlay-diagnostics.previous.jsonl");
      Api.prefs(context)
          .edit()
          .putString("server", "http://10.0.2.2:3211")
          .putString("token", "local-emulator-test-token-00000001")
          .putString("quality", "standard")
          .putBoolean("bootStart", false)
          .putBoolean("autoPlay", false)
          .putBoolean("floating", true)
          .putBoolean("floatingLarge", false)
          .putBoolean("floatingLyrics", false)
          .putBoolean("overlayCarCompat", true)
          .commit();
      require(
          "legacy insecure lock allowed in compatibility mode",
          !FloatingPlayer.lockBlocks(true, false, true, false));
      require(
          "secure lock still blocks in compatibility mode",
          FloatingPlayer.lockBlocks(true, true, true, false));
      require(
          "normal mode still blocks insecure lock",
          FloatingPlayer.lockBlocks(true, false, false, false));
      require(
          "unlocked secure device remains eligible",
          !FloatingPlayer.lockBlocks(false, true, true, false));
      require("matches logged CS11 hardware", FloatingPlayer.isCs11(19, "Freescale", "CS11"));
      require("profile excludes modern Android", !FloatingPlayer.isCs11(30, "Freescale", "CS11"));
      require("profile excludes other manufacturer", !FloatingPlayer.isCs11(19, "Xiaomi", "CS11"));
      require("profile excludes other models", !FloatingPlayer.isCs11(19, "Freescale", "other"));
      require(
          "CS11 observed dual lock flags no longer block in compat mode",
          !FloatingPlayer.lockBlocks(
              true, true, true, FloatingPlayer.isCs11(19, "Freescale", "CS11")));
      require(
          "CS11 compat off restores lock restriction",
          FloatingPlayer.lockBlocks(true, true, false, true));
      require(
          "CS11 dual lock flags get show-when-locked window",
          FloatingPlayer.showOverKeyguard(true, true, true));
      require(
          "other secure devices do not get show-when-locked window",
          !FloatingPlayer.showOverKeyguard(true, true, false));
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
      tracks.add(
          new Track(
              new JSONObject()
                  .put("id", "1")
                  .put("name", "private-test-song")
                  .put("artist", "private-test-artist")
                  .put("duration", 60000)
                  .put("cover", "")));
      ui(
          new Work() {
            public void run() {
              service.setListener(null);
              service.playQueue(tracks, 0, false, "", 0, false);
            }
          });
      check(
          "fixture playback starts",
          new Check() {
            public boolean ok() {
              return service.isPlaying();
            }
          });
      check(
          "foreground hides card",
          new Check() {
            public boolean ok() throws Exception {
              return card() == null;
            }
          });
      ui(
          new Work() {
            public void run() {
              callActivityOnPause(activity);
            }
          });
      waitCard();
      ui(
          new Work() {
            public void run() throws Exception {
              require("compat window has show-when-locked flag", flag());
            }
          });
      ui(
          new Work() {
            public void run() {
              callActivityOnResume(activity);
            }
          });
      check(
          "resume removes card",
          new Check() {
            public boolean ok() throws Exception {
              return card() == null;
            }
          });
      mode(true, true);
      ui(
          new Work() {
            public void run() {
              callActivityOnPause(activity);
            }
          });
      waitCard();
      ui(
          new Work() {
            public void run() throws Exception {
              require(
                  "lyrics created from pause callback", (Boolean) get(floating(), "lyricsOnly"));
              BroadcastReceiver receiver = (BroadcastReceiver) get(floating(), "screen");
              receiver.onReceive(context, new Intent(Intent.ACTION_SCREEN_OFF));
            }
          });
      check(
          "screen-off receiver removes lyrics",
          new Check() {
            public boolean ok() throws Exception {
              return card() == null;
            }
          });
      ui(
          new Work() {
            public void run() {
              service.updateFloatingPlayer();
            }
          });
      check(
          "periodic tick cannot recreate screen-off overlay",
          new Check() {
            public boolean ok() throws Exception {
              return card() == null;
            }
          });
      ui(
          new Work() {
            public void run() throws Exception {
              ((BroadcastReceiver) get(floating(), "screen"))
                  .onReceive(context, new Intent(Intent.ACTION_SCREEN_ON));
            }
          });
      waitCard();
      mode(true, false);
      waitCard();
      ui(
          new Work() {
            public void run() throws Exception {
              require("disabling compatibility removes lockscreen flag", !flag());
            }
          });
      ui(
          new Work() {
            public void run() {
              callActivityOnResume(activity);
              service.pause();
            }
          });
      result.putString(
          "stream", "\nPASS " + passed.size() + " overlay compatibility checks\n" + passed + "\n");
      finish(Activity.RESULT_OK, result);
    } catch (Throwable t) {
      result.putString("stream", "\nFAIL after " + passed.size() + ": " + t + "\n" + passed + "\n");
      finish(Activity.RESULT_CANCELED, result);
    }
  }
}
