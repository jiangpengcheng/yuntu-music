package io.github.yuntumusic.direct;

import android.app.*;
import android.content.*;
import android.media.AudioManager;
import android.os.*;
import android.view.View;
import android.widget.TextView;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.json.*;

/** A vendor Binder fixture, not proof of CS11 speakers or a phone's AVRCP behavior. */
public final class BluetoothTest extends MediaOutputTest {
  final Vendor vendor = new Vendor();
  BluetoothSource source;
  FakeContext fake;

  final class FakeContext extends ContextWrapper {
    int binds, unbinds;

    FakeContext(Context c) {
      super(c);
    }

    public boolean bindService(Intent intent, final ServiceConnection connection, int flags) {
      binds++;
      if (!intent.getComponent().getClassName().equals("ecarx.bluetooth.service.a2dp.A2dpService"))
        throw new AssertionError("wrong service");
      new Handler(Looper.getMainLooper())
          .post(
              new Runnable() {
                public void run() {
                  connection.onServiceConnected(
                      new ComponentName("ecarx.bluetooth.service", "fixture"), vendor);
                }
              });
      return true;
    }

    public void unbindService(ServiceConnection connection) {
      unbinds++;
    }
  }

  OemContext oemContext;

  final class OemContext extends ContextWrapper {
    volatile int plays;
    int unbinds;
    volatile boolean reject, offMain = true;

    OemContext(Context c) {
      super(c);
    }

    final Binder session =
        new Binder() {
          {
            attachInterface(null, "android.support.v4.media.session.IMediaSession");
          }

          protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
              throws RemoteException {
            if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags);
            if (reject) return false;
            if (code != 26) throw new AssertionError("wrong session command");
            data.enforceInterface("android.support.v4.media.session.IMediaSession");
            if (!"CUSTOM_PLAY_BT_ACTION".equals(data.readString()) || data.readInt() != 0)
              throw new AssertionError("wrong Bluetooth source action");
            offMain &= Looper.myLooper() != Looper.getMainLooper();
            plays++;
            vendor.playing = true;
            reply.writeNoException();
            return true;
          }
        };
    final Messenger browser =
        new Messenger(
            new Handler(Looper.getMainLooper()) {
              public void handleMessage(Message message) {
                if (message.what == 2) return;
                try {
                  if (message.what != 1
                      || !context
                          .getPackageName()
                          .equals(message.getData().getString("data_package_name")))
                    throw new AssertionError("wrong browser connect");
                  Bundle data = new Bundle();
                  data.putParcelable(
                      "data_media_session_token",
                      new android.support.v4.media.session.MediaSessionCompat.Token(session));
                  Parcel wire = Parcel.obtain();
                  data.writeToParcel(wire, 0);
                  wire.setDataPosition(0);
                  Bundle decoded = Bundle.CREATOR.createFromParcel(wire);
                  wire.recycle();
                  Message reply = Message.obtain();
                  reply.what = 1;
                  reply.arg1 = 1;
                  reply.setData(decoded);
                  message.replyTo.send(reply);
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              }
            });

    public boolean bindService(Intent intent, final ServiceConnection connection, int flags) {
      if (!intent.getComponent().getPackageName().equals("com.ecarx.multimedia"))
        throw new AssertionError("wrong OEM package");
      new Handler(Looper.getMainLooper())
          .post(
              new Runnable() {
                public void run() {
                  connection.onServiceConnected(
                      new ComponentName("com.ecarx.multimedia", "fixture"), browser.getBinder());
                }
              });
      return true;
    }

    public void unbindService(ServiceConnection connection) {
      unbinds++;
    }
  }

  final class Vendor extends Binder {
    volatile IBinder callback;
    volatile boolean connected = true, playing, inCall, accept = true, stall;
    volatile boolean offMain = true;
    volatile String title = "蓝牙测试歌曲", artist = "手机歌手", album = "手机专辑";
    volatile long duration = 180000, position = 22000;
    final List<Integer> commands = Collections.synchronizedList(new ArrayList<Integer>());
    final List<Integer> calls = Collections.synchronizedList(new ArrayList<Integer>());

    Vendor() {
      attachInterface(null, BluetoothSource.DESCRIPTOR);
    }

    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
        throws RemoteException {
      if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags);
      offMain &= Looper.myLooper() != Looper.getMainLooper();
      data.enforceInterface(BluetoothSource.DESCRIPTOR);
      calls.add(code);
      while (stall) SystemClock.sleep(25);
      reply.writeNoException();
      switch (code) {
        case 3:
          reply.writeInt(connected ? 1 : 0);
          if (connected) {
            reply.writeInt(1);
            reply.writeString("12:34:56:78:9A:BC");
          }
          break;
        case 8:
          if (data.readInt() != 1 || !"12:34:56:78:9A:BC".equals(data.readString()))
            throw new AssertionError("bad device parcel");
          reply.writeInt(playing ? 1 : 0);
          break;
        case 9:
          int command = data.readInt();
          commands.add(command);
          if (accept && command == 0) playing = true;
          if (accept && command == 2) playing = false;
          reply.writeInt(accept ? 1 : 0);
          break;
        case 10:
          reply.writeInt(connected ? 1 : 0);
          break;
        case 11:
          event(0, true);
          reply.writeInt(1);
          break;
        case 12:
          event(1, true);
          reply.writeInt(1);
          break;
        case 13:
          reply.writeInt(inCall ? 1 : 0);
          break;
        case 14:
          callback = data.readStrongBinder();
          break;
        case 15:
          if (callback != data.readStrongBinder())
            throw new AssertionError("wrong unregister callback");
          callback = null;
          break;
        case 16:
        case 17:
          break;
        default:
          throw new AssertionError("Unexpected/mutating Bluetooth transaction " + code);
      }
      return true;
    }

    void event(int event, boolean present) throws RemoteException {
      IBinder cb = callback;
      if (cb == null) return;
      emit(cb, event, present);
    }

    void emit(IBinder cb, int event, boolean present) throws RemoteException {
      Parcel data = Parcel.obtain(), reply = Parcel.obtain();
      try {
        data.writeInterfaceToken(BluetoothSource.CALLBACK);
        data.writeInt(event);
        data.writeInt(present ? 1 : 0);
        if (present) {
          data.writeString(title);
          data.writeString(artist);
          data.writeString(album);
          data.writeLong(duration);
          data.writeLong(position);
        }
        data.writeLong(position);
        cb.transact(1, data, reply, 0);
        reply.readException();
      } finally {
        data.recycle();
        reply.recycle();
      }
    }
  }

  void verify(String name, boolean ok) {
    if (!ok) throw new AssertionError(name);
    passed.add(name);
  }

  long requests() throws Exception {
    Field f = Api.class.getDeclaredField("REQUEST_IDS");
    f.setAccessible(true);
    return ((AtomicLong) f.get(null)).get();
  }

  void selectFake() throws Exception {
    ui(
        new Work() {
          public void run() throws Exception {
            service.selectSource(true);
            ((BluetoothSource) get(service, "bluetooth")).close(false);
            fake = new FakeContext(context);
            source =
                new BluetoothSource(
                    fake,
                    new BluetoothSource.Listener() {
                      public void changed() {
                        try {
                          Method m = PlaybackService.class.getDeclaredMethod("bluetoothChanged");
                          m.setAccessible(true);
                          m.invoke(service);
                        } catch (Exception e) {
                          throw new RuntimeException(e);
                        }
                      }
                    });
            set(service, "bluetooth", source);
            source.open();
          }
        });
    check(
        "vendor callback supplies metadata",
        new Check() {
          public boolean ok() {
            return service.current().name.equals("蓝牙测试歌曲")
                && service.current().artist.equals("手机歌手");
          }
        });
  }

  public void onStart() {
    Bundle result = new Bundle();
    try {
      context = getTargetContext();
      FixtureTransport.install(context);
      Api.prefs(context)
          .edit()
          .clear()
          .putBoolean("autoPlay", false)
          .putString("quality", "standard")
          .commit();
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
          new Track(new JSONObject().put("id", "1").put("name", "网易云原队列").put("duration", 60000)));
      ui(
          new Work() {
            public void run() {
              service.playQueue(tracks, 0, false, "", 0, false);
            }
          });
      check(
          "cloud playback starts",
          new Check() {
            public boolean ok() {
              return service.isPlaying();
            }
          });
      ui(
          new Work() {
            public void run() {
              service.selectSource(true);
            }
          });
      check(
          "unsupported hardware explains limitation",
          new Check() {
            public boolean ok() {
              return service.isBluetooth() && service.status().contains("CS11");
            }
          });
      check(
          "source switch releases local decoder",
          new Check() {
            public boolean ok() throws Exception {
              return get(service, "player") == null;
            }
          });
      selectFake();
      check(
          "position and duration from AVRCP",
          new Check() {
            public boolean ok() {
              return service.duration() == 180000 && service.position() == 22000;
            }
          });
      check(
          "source selection does not autoplay phone",
          new Check() {
            public boolean ok() {
              return vendor.commands.isEmpty() && !service.isPlaying();
            }
          });
      check(
          "queue and cloud controls unavailable in Bluetooth",
          new Check() {
            public boolean ok() throws Exception {
              return service.tracks().isEmpty()
                  && !((View) get(activity, "likeButton")).isEnabled()
                  && !((View) get(activity, "saveButton")).isEnabled()
                  && !((View) get(activity, "seek")).isEnabled()
                  && !((View) get(activity, "mode")).isEnabled()
                  && !((View) get(activity, "queueButton")).isEnabled();
            }
          });
      verify(
          "no lyrics assumed from ordinary metadata",
          source.presentation().optString("plain").isEmpty());
      screenshot("bluetooth-player.png");
      vendor.connected = false;
      check(
          "autoplay fixture disconnects",
          new Check() {
            public boolean ok() {
              return !service.bluetoothReady();
            }
          });
      ui(
          new Work() {
            public void run() throws Exception {
              Api.prefs(context).edit().putBoolean("autoPlay", true).apply();
              StartupPlayback policy =
                  new StartupPlayback(
                      service,
                      new StartupPlayback.Network() {
                        public boolean connected() {
                          throw new AssertionError(
                              "Bluetooth autoplay must not depend on internet");
                        }
                      });
              set(service, "startup", policy);
              policy.begin();
            }
          });
      Thread.sleep(300);
      verify("Bluetooth autoplay waits for connected phone", vendor.commands.isEmpty());
      vendor.connected = true;
      check(
          "Bluetooth autoplay works without internet",
          new Check() {
            public boolean ok() {
              return service.isPlaying() && vendor.commands.contains(0);
            }
          });
      ui(
          new Work() {
            public void run() {
              service.pause();
            }
          });
      check(
          "manual pause cancels Bluetooth autoplay",
          new Check() {
            public boolean ok() {
              return !service.isPlaying();
            }
          });
      final int autoPlays = Collections.frequency(vendor.commands, 0);
      ui(
          new Work() {
            public void run() {
              service.startAfterLaunch();
            }
          });
      Thread.sleep(1200);
      verify(
          "automatic Bluetooth playback attempts once",
          Collections.frequency(vendor.commands, 0) == autoPlays);
      long before = requests();
      ui(
          new Work() {
            public void run() {
              service.play();
            }
          });
      check(
          "play routed to phone",
          new Check() {
            public boolean ok() {
              return vendor.commands.contains(0) && service.isPlaying();
            }
          });
      verify("without stock player Yuntu owns audio focus", (Boolean) get(service, "hasFocus"));
      verify("render enabled before play", vendor.calls.indexOf(16) < vendor.calls.indexOf(9));
      ui(
          new Work() {
            public void run() {
              service.pause();
            }
          });
      check(
          "pause confirmed by phone",
          new Check() {
            public boolean ok() {
              return vendor.commands.contains(2) && !service.isPlaying();
            }
          });
      ui(
          new Work() {
            public void run() {
              service.next(true);
              service.previous();
              service.seek(1000);
              service.cycleMode();
            }
          });
      check(
          "next and previous use AVRCP ordinals",
          new Check() {
            public boolean ok() {
              return vendor.commands.contains(3) && vendor.commands.contains(4);
            }
          });
      verify("no cloud requests for Bluetooth metadata lyrics or controls", requests() == before);
      final int directPlays = Collections.frequency(vendor.commands, 0);
      ui(
          new Work() {
            public void run() throws Exception {
              oemContext = new OemContext(context);
              OemBluetoothPlayer original =
                  new OemBluetoothPlayer(oemContext, (Handler) get(source, "worker"));
              set(source, "oem", original);
              original.open();
              service.play();
            }
          });
      check(
          "API 19 original player selects Bluetooth through media session",
          new Check() {
            public boolean ok() {
              return oemContext.plays == 1 && service.isPlaying();
            }
          });
      verify(
          "OEM play does not also send direct AVRCP play",
          Collections.frequency(vendor.commands, 0) == directPlays);
      verify("OEM wire command runs off main thread", oemContext.offMain);
      ui(
          new Work() {
            public void run() {
              service.pause();
            }
          });
      check(
          "pause after OEM play",
          new Check() {
            public boolean ok() {
              return !service.isPlaying();
            }
          });
      final int beforeCancelledPlay = oemContext.plays;
      ui(
          new Work() {
            public void run() {
              service.play();
              service.pause();
            }
          });
      Thread.sleep(700);
      verify(
          "manual pause cancels queued original-player play",
          oemContext.plays == beforeCancelledPlay && !vendor.playing);
      oemContext.reject = true;
      ui(
          new Work() {
            public void run() {
              service.play();
            }
          });
      check(
          "unsupported OEM play falls back to direct AVRCP",
          new Check() {
            public boolean ok() {
              return Collections.frequency(vendor.commands, 0) == directPlays + 1
                  && service.isPlaying();
            }
          });
      ui(
          new Work() {
            public void run() {
              service.pause();
            }
          });
      check(
          "pause after direct fallback",
          new Check() {
            public boolean ok() {
              return !service.isPlaying();
            }
          });
      vendor.inCall = true;
      final int plays = Collections.frequency(vendor.commands, 0);
      ui(
          new Work() {
            public void run() {
              service.play();
            }
          });
      check(
          "phone calls block play command",
          new Check() {
            public boolean ok() {
              return service.status().contains("通话")
                  && Collections.frequency(vendor.commands, 0) == plays;
            }
          });
      vendor.inCall = false;
      vendor.accept = false;
      ui(
          new Work() {
            public void run() {
              service.next(true);
            }
          });
      check(
          "rejected control is explained",
          new Check() {
            public boolean ok() {
              return service.status().contains("未接受");
            }
          });
      vendor.accept = true;
      ui(
          new Work() {
            public void run() {
              Api.prefs(context)
                  .edit()
                  .putBoolean("bluetoothTitleLyrics", true)
                  .putBoolean("floating", true)
                  .apply();
              service.setUiVisible(false);
            }
          });
      vendor.title = "手机传来的当前歌词";
      vendor.event(0, true);
      check(
          "received current lyric in player",
          new Check() {
            public boolean ok() {
              return "手机传来的当前歌词".equals(service.bluetoothPresentation().optString("plain"));
            }
          });
      check(
          "card shows received current lyric",
          new Check() {
            public boolean ok() throws Exception {
              return card() != null && lyricText().equals("手机传来的当前歌词");
            }
          });
      ui(
          new Work() {
            public void run() {
              Api.prefs(context).edit().putBoolean("floatingLyrics", true).apply();
              service.updateFloatingPlayer();
            }
          });
      check(
          "lyric overlay shows only received sentence",
          new Check() {
            public boolean ok() throws Exception {
              return lyricText().equals("手机传来的当前歌词")
                  && ((TextView) get(floating(), "nextLyric")).getText().length() == 0;
            }
          });
      vendor.title = "[00:00.00]第一句\n[00:25.00]下一句";
      vendor.event(0, true);
      check(
          "timed lyrics parsed locally",
          new Check() {
            public boolean ok() throws Exception {
              return "第一句".equals(lyricText())
                  && "下一句".equals(((TextView) get(floating(), "nextLyric")).getText().toString());
            }
          });
      vendor.connected = false;
      check(
          "disconnect clears metadata and lyrics",
          new Check() {
            public boolean ok() {
              return !service.bluetoothReady()
                  && service.current().name.equals("蓝牙音乐")
                  && service.bluetoothPresentation().optString("plain").isEmpty();
            }
          });
      final int commandCount = vendor.commands.size();
      ui(
          new Work() {
            public void run() {
              service.next(true);
            }
          });
      Thread.sleep(400);
      verify("disconnected controls do not reach phone", vendor.commands.size() == commandCount);
      vendor.title = "重新连接后的歌曲";
      vendor.connected = true;
      check(
          "reconnect refreshes metadata",
          new Check() {
            public boolean ok() {
              return service.current().name.equals("重新连接后的歌曲");
            }
          });
      vendor.duration = 0xffffffffL;
      vendor.position = -1;
      vendor.event(1, true);
      check(
          "unknown AVRCP time becomes zero",
          new Check() {
            public boolean ok() {
              return service.duration() == 0 && service.position() == 0;
            }
          });
      vendor.duration = 180000;
      vendor.position = 22000;
      ui(
          new Work() {
            public void run() {
              service.play();
            }
          });
      check(
          "resume before focus test",
          new Check() {
            public boolean ok() {
              return service.isPlaying();
            }
          });
      final int focusCommands = vendor.commands.size();
      ui(
          new Work() {
            public void run() {
              service.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
              service.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
            }
          });
      Thread.sleep(300);
      verify(
          "Bluetooth leaves audio focus to original player", !(Boolean) get(service, "hasFocus"));
      verify(
          "delayed local focus events do not interrupt phone",
          vendor.commands.size() == focusCommands);
      ui(
          new Work() {
            public void run() throws Exception {
              ((OemBluetoothPlayer) get(source, "oem")).close();
              set(source, "oem", null);
              service.play();
            }
          });
      check(
          "deleted stock player uses direct audio focus",
          new Check() {
            public boolean ok() throws Exception {
              return service.isPlaying() && (Boolean) get(service, "hasFocus");
            }
          });
      ui(
          new Work() {
            public void run() {
              service.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
            }
          });
      check(
          "direct Bluetooth pauses for navigation",
          new Check() {
            public boolean ok() {
              return !service.isPlaying();
            }
          });
      ui(
          new Work() {
            public void run() {
              service.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
            }
          });
      check(
          "direct Bluetooth resumes after navigation",
          new Check() {
            public boolean ok() {
              return service.isPlaying();
            }
          });
      vendor.stall = true;
      check(
          "stalled vendor reports timeout without UI ANR",
          new Check() {
            public boolean ok() {
              return service.status().contains("响应超时");
            }
          });
      final IBinder late = vendor.callback;
      ui(
          new Work() {
            public void run() {
              service.selectSource(false);
              service.setUiVisible(true);
            }
          });
      check(
          "cloud queue preserved after source switch",
          new Check() {
            public boolean ok() {
              return !service.isBluetooth()
                  && "1".equals(service.current().id)
                  && !service.isPlaying();
            }
          });
      verify("source selection remains responsive during stuck Binder", !service.isBluetooth());
      vendor.stall = false;
      check(
          "source switch unregisters vendor callback",
          new Check() {
            public boolean ok() {
              return vendor.callback == null && fake.unbinds == 1;
            }
          });
      vendor.title = "过期蓝牙歌词";
      vendor.emit(late, 0, true);
      Thread.sleep(200);
      verify(
          "late Bluetooth event cannot overwrite cloud", service.current().name.equals("网易云原队列"));
      ui(
          new Work() {
            public void run() {
              service.play();
            }
          });
      check(
          "cloud playback resumes normally",
          new Check() {
            public boolean ok() {
              return service.isPlaying();
            }
          });
      verify("all vendor IPC runs off UI thread", vendor.offMain);
      verify("original player binding released", oemContext.unbinds == 1);
      verify(
          "no pairing or connection mutation",
          !vendor.calls.contains(1) && !vendor.calls.contains(2) && !vendor.calls.contains(6));
      result.putString("stream", "\nPASS " + passed.size() + " Bluetooth checks\n" + passed + "\n");
      finish(Activity.RESULT_OK, result);
    } catch (Throwable t) {
      result.putString("stream", "\nFAIL after " + passed.size() + ": " + t + "\n" + passed + "\n");
      finish(Activity.RESULT_CANCELED, result);
    } finally {
      vendor.stall = false;
    }
  }
}
