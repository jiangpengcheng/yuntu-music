package io.github.yuntumusic.direct;

import android.bluetooth.BluetoothAdapter;
import android.content.*;
import android.os.*;
import android.util.Log;
import org.json.*;

/** CS11 ECARX A2DP sink protocol. Audio stays in the original car Bluetooth service. */
final class BluetoothSource {
  interface Listener {
    void changed();
  }

  static final String DESCRIPTOR = "ecarx.bluetooth.IBluetoothA2dp";
  static final String CALLBACK = "ecarx.bluetooth.IBluetoothAvrcpEventListener";
  static final int PLAY = 0, PAUSE = 2, NEXT = 3, PREVIOUS = 4;
  private final Context context;
  private final Listener listener;
  private final Handler main = new Handler(Looper.getMainLooper());
  private static HandlerThread sharedThread;

  private static synchronized Looper workerLooper() {
    if (sharedThread == null) {
      sharedThread = new HandlerThread("YuntuBluetooth");
      sharedThread.start();
    }
    return sharedThread.getLooper();
  }

  private final Handler worker;
  private volatile boolean closed;
  private boolean bound, connected, playing, receiverRegistered, positionKnown;
  private long playbackStatusAt;
  private boolean reportedPlaying;
  private volatile int epoch, controlVersion;
  private int revision;
  private long positionAt;
  private int position, duration;
  private String title = "", artist = "", album = "", status = "正在连接车机蓝牙服务…";
  private OemBluetoothPlayer oem;
  private String peer = ""; // Worker-owned; never logged or persisted.
  private IBinder remote, callback; // Worker-owned.
  private String failure = "";
  private long busySince; // Main-thread watchdog, no new worker/thread on a stalled Binder.

  BluetoothSource(Context context, Listener listener) {
    this.context = context;
    this.listener = listener;
    worker = new Handler(workerLooper());
  }

  private final ServiceConnection connection =
      new ServiceConnection() {
        public void onServiceConnected(ComponentName name, final IBinder binder) {
          if (closed) return;
          final int ticket = ++epoch;
          busySince = SystemClock.elapsedRealtime();
          worker.post(
              new Runnable() {
                public void run() {
                  try {
                    if (!DESCRIPTOR.equals(binder.getInterfaceDescriptor()))
                      throw new RemoteException("unexpected_interface");
                    remote = binder;
                    callback = new Events(ticket);
                    call(14, callback, false);
                    poll(ticket);
                  } catch (Exception e) {
                    fail(ticket, e);
                  }
                }
              });
        }

        public void onServiceDisconnected(ComponentName name) {
          if (closed) return;
          ++epoch;
          reset();
          status = "车机蓝牙服务已断开，等待恢复";
          listener.changed();
        }
      };

  private final BroadcastReceiver playbackEvents =
      new BroadcastReceiver() {
        public void onReceive(Context c, Intent intent) {
          if (closed || !connected) return;
          String action = intent.getAction();
          if (!"ecarx.bluetooth.service.a2dp.avrcp_action_play".equals(action)
              && !"ecarx.bluetooth.service.a2dp.avrcp_action_pause".equals(action)) return;
          position = position();
          positionAt = SystemClock.elapsedRealtime();
          playbackStatusAt = positionAt;
          reportedPlaying = action.endsWith("_play");
          playing = reportedPlaying;
          status = playing ? "蓝牙 · 正在播放" : "蓝牙 · 已暂停";
          listener.changed();
        }
      };

  void open() {
    Intent intent =
        new Intent(DESCRIPTOR)
            .setComponent(
                new ComponentName(
                    "ecarx.bluetooth.service", "ecarx.bluetooth.service.a2dp.A2dpService"));
    try {
      bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
      if (!bound) status = "此设备没有 CS11 蓝牙接收服务，请在车机上使用";
    } catch (RuntimeException e) {
      status = "无法访问车机蓝牙服务：" + e.getClass().getSimpleName();
      Log.w("YuntuBluetooth", "bind_failed type=" + e.getClass().getSimpleName());
    }
    if (bound) {
      busySince = SystemClock.elapsedRealtime();
      IntentFilter filter = new IntentFilter("ecarx.bluetooth.service.a2dp.avrcp_action_play");
      filter.addAction("ecarx.bluetooth.service.a2dp.avrcp_action_pause");
      context.registerReceiver(playbackEvents, filter);
      receiverRegistered = true;
    }
    if (bound && Build.VERSION.SDK_INT == 19) {
      oem = new OemBluetoothPlayer(context, worker);
      oem.open();
    }
    main.post(watchdog);
    listener.changed();
  }

  private final Runnable watchdog =
      new Runnable() {
        public void run() {
          if (closed) return;
          if (busySince != 0 && SystemClock.elapsedRealtime() - busySince > 7000) {
            reset();
            status = "车机蓝牙服务响应超时，请检查原车蓝牙";
            listener.changed();
          }
          main.postDelayed(this, 1000);
        }
      };

  private boolean active(int ticket) {
    return !closed && epoch == ticket;
  }

  private void post(final int ticket, final Runnable action) {
    main.post(
        new Runnable() {
          public void run() {
            if (!active(ticket)) return;
            busySince = 0;
            action.run();
            listener.changed();
          }
        });
  }

  private void poll(final int ticket) {
    if (!active(ticket)) return;
    main.post(
        new Runnable() {
          public void run() {
            if (active(ticket)) busySince = SystemClock.elapsedRealtime();
          }
        });
    try {
      final String next = (String) call(3, null, false);
      final boolean control = next.length() > 0 && (Boolean) call(10, null, false);
      final boolean running = next.length() > 0 && (Boolean) call(8, next, false);
      final boolean changedPeer = !peer.equals(next);
      peer = next;

      post(
          ticket,
          new Runnable() {
            public void run() {
              if (changedPeer || (next.length() == 0 && connected)) reset();
              connected = next.length() > 0;
              boolean effective =
                  connected
                      && (SystemClock.elapsedRealtime() - playbackStatusAt < 5000
                          ? reportedPlaying
                          : running);
              if (playing != effective) {
                position = position();
                positionAt = SystemClock.elapsedRealtime();
              }
              playing = effective;
              status =
                  !connected
                      ? "请在车机蓝牙设置中连接手机，并开启媒体音频"
                      : !control ? "手机已连接，等待蓝牙媒体控制就绪" : playing ? "蓝牙 · 正在播放" : "蓝牙 · 已连接，点击播放";
            }
          });
      if (control) {
        call(11, null, false);
        call(12, null, false);
      }
      failure = "";
    } catch (Exception e) {
      fail(ticket, e);
    }
    if (active(ticket))
      worker.postDelayed(
          new Runnable() {
            public void run() {
              poll(ticket);
            }
          },
          2000);
  }

  private void fail(final int ticket, Exception error) {
    final String type = error.getClass().getSimpleName();
    if (!failure.equals(type)) Log.w("YuntuBluetooth", "ipc_failed type=" + type);
    failure = type;
    post(
        ticket,
        new Runnable() {
          public void run() {
            reset();
            status = "蓝牙服务暂不可用（" + type + "），请检查原车蓝牙";
          }
        });
  }

  void command(final int command) {
    controlVersion++;
    if (command != PLAY && oem != null) oem.cancelPlay();
    command(command, true);
  }

  private void command(final int command, final boolean useOem) {
    if (closed || !bound) return;
    final int ticket = epoch;
    final int controlTicket = controlVersion;
    worker.post(
        new Runnable() {
          public void run() {
            if (!active(ticket) || (command == PLAY && controlTicket != controlVersion)) return;
            try {
              // Re-read the peer before every control: do not act on a disconnected/replaced phone.
              String currentPeer = (String) call(3, null, false);
              if (peer.length() == 0
                  || !peer.equals(currentPeer)
                  || !(Boolean) call(10, null, false)) {
                post(
                    ticket,
                    new Runnable() {
                      public void run() {
                        status = "手机蓝牙媒体尚未就绪，请连接后重试";
                      }
                    });
                return;
              }
              if (command == PLAY) {
                if ((Boolean) call(13, null, false)) {
                  post(
                      ticket,
                      new Runnable() {
                        public void run() {
                          status = "正在通话，请结束通话后播放";
                        }
                      });
                  return;
                }
                if (useOem && oem != null) {
                  post(
                      ticket,
                      new Runnable() {
                        public void run() {
                          if (controlTicket != controlVersion) return;
                          busySince = SystemClock.elapsedRealtime();
                          oem.play(
                              new OemBluetoothPlayer.Result() {
                                public void done(boolean accepted) {
                                  if (!active(ticket) || controlTicket != controlVersion) return;
                                  busySince = 0;
                                  if (!accepted) command(PLAY, false);
                                  else {
                                    status = "已请求原车蓝牙播放，等待手机响应";
                                    listener.changed();
                                  }
                                }
                              });
                        }
                      });
                  return;
                }
                call(16, null, false);
              }
              final boolean accepted = (Boolean) call(9, Integer.valueOf(command), false);
              post(
                  ticket,
                  new Runnable() {
                    public void run() {
                      status = accepted ? "蓝牙指令已发送，等待手机响应" : "手机未接受蓝牙指令，请在手机播放器操作";
                    }
                  });
            } catch (Exception e) {
              fail(ticket, e);
            }
          }
        });
  }

  /** Only transactions observed in the CS11 service. No connect, disconnect or pairing calls. */
  private Object call(int code, Object argument, boolean cleanup) throws RemoteException {
    if (remote == null || (closed && !cleanup)) throw new RemoteException("service_unavailable");
    Parcel data = Parcel.obtain(), reply = Parcel.obtain();
    try {
      data.writeInterfaceToken(DESCRIPTOR);
      if (argument instanceof IBinder) data.writeStrongBinder((IBinder) argument);
      else if (argument instanceof Integer) data.writeInt((Integer) argument);
      else if (argument instanceof String) {
        data.writeInt(1);
        data.writeString((String) argument);
      }
      if (!remote.transact(code, data, reply, 0))
        throw new RemoteException("unsupported_transaction");
      reply.readException();
      if (code == 3) {
        int count = reply.readInt();
        if (count < 0) return "";
        if (count > 32) throw new RemoteException("invalid_device_count");
        String first = "";
        for (int i = 0; i < count; i++)
          if (reply.readInt() != 0) {
            String address = reply.readString();
            if (first.length() == 0 && BluetoothAdapter.checkBluetoothAddress(address))
              first = address;
          }
        return first;
      }
      if (code >= 8 && code <= 13) return reply.readInt() != 0;
      return null;
    } finally {
      data.recycle();
      reply.recycle();
    }
  }

  private final class Events extends Binder {
    private final int ticket;

    Events(int ticket) {
      this.ticket = ticket;
    }

    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
        throws RemoteException {
      if (code == INTERFACE_TRANSACTION) {
        if (reply != null) reply.writeString(CALLBACK);
        return true;
      }
      if (code != 1) return super.onTransact(code, data, reply, flags);
      data.enforceInterface(CALLBACK);
      final int event = data.readInt();
      boolean present = data.readInt() != 0;
      final String name = present ? clean(data.readString()) : "";
      final String singer = present ? clean(data.readString()) : "";
      final String record = present ? clean(data.readString()) : "";
      final int length = present ? milliseconds(data.readLong()) : 0;
      if (present)
        data.readLong(); // songPos; callback's final argument is the authoritative position.
      final long rawElapsed = data.readLong();
      final int elapsed = milliseconds(rawElapsed);
      final boolean known = rawElapsed >= 0 && rawElapsed < 0xffffffffL;
      post(
          ticket,
          new Runnable() {
            public void run() {
              if (!connected) return;
              if (event == 0) {
                if (!title.equals(name) || !artist.equals(singer) || !album.equals(record)) {
                  title = name;
                  artist = singer;
                  album = record;
                  revision++;
                }
              } else if (event == 1) {
                duration = length;
                positionKnown = known;
                position = elapsed;
                positionAt = SystemClock.elapsedRealtime();
              } else if (event == 2) {
                clearMetadata();
              }
            }
          });
      if (reply != null) reply.writeNoException();
      return true;
    }
  }

  static int milliseconds(long value) {
    return value < 0 || value >= 0xffffffffL ? 0 : (int) Math.min(value, Integer.MAX_VALUE);
  }

  private static String clean(String value) {
    return value == null ? "" : value.substring(0, Math.min(value.length(), 4096)).trim();
  }

  private void clearMetadata() {
    title = artist = album = "";
    duration = position = 0;
    positionKnown = false;
    positionAt = SystemClock.elapsedRealtime();
    revision++;
  }

  private void reset() {
    connected = playing = false;
    playbackStatusAt = 0;
    clearMetadata();
  }

  boolean usesOriginalPlayer() {
    return oem != null && oem.available();
  }

  void focusDenied() {
    status = "其他音源正在使用音频，请稍后播放";
    listener.changed();
  }

  boolean isPlaying() {
    return playing;
  }

  boolean ready() {
    return connected;
  }

  String status() {
    return status;
  }

  int duration() {
    return duration;
  }

  int position() {
    long value = position;
    // Do not invent progress indefinitely if the phone stops sending position updates.
    if (playing && positionKnown && positionAt > 0)
      value += Math.min(3000, SystemClock.elapsedRealtime() - positionAt);
    return (int) Math.min(duration > 0 ? duration : Integer.MAX_VALUE, value);
  }

  Track track() {
    try {
      return new Track(
          new JSONObject()
              .put("id", "bluetooth:" + revision)
              .put("name", title.length() == 0 ? "蓝牙音乐" : title)
              .put("artist", artist.length() == 0 ? "手机 → 车机" : artist)
              .put("album", album)
              .put("duration", duration));
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  JSONObject presentation() {
    try {
      boolean useTitle = Api.prefs(context).getBoolean("bluetoothTitleLyrics", false);
      JSONObject result = NativeApi.lyrics(useTitle && connected ? title : "");
      return result.put(
          "message",
          !connected ? "连接手机蓝牙后播放" : useTitle ? "等待手机通过蓝牙歌名发送歌词" : "手机未提供歌词\n可在设置中启用蓝牙歌名歌词");
    } catch (Exception e) {
      return new JSONObject();
    }
  }

  void close(final boolean pausePhone) {
    if (closed) return;
    closed = true;
    ++epoch;
    main.removeCallbacksAndMessages(null);
    if (receiverRegistered) {
      context.unregisterReceiver(playbackEvents);
      receiverRegistered = false;
    }
    worker.removeCallbacksAndMessages(null);
    if (oem != null) {
      oem.close();
      oem = null;
    }
    // Unbind immediately on main; even a stuck vendor Binder cannot freeze UI/keep a binding alive.
    if (bound) {
      try {
        context.unbindService(connection);
      } catch (RuntimeException ignored) {
      }
      bound = false;
    }
    worker.post(
        new Runnable() {
          public void run() {
            try {
              if (pausePhone
                  && remote != null
                  && peer.length() > 0
                  && peer.equals(call(3, null, true))) {
                call(9, Integer.valueOf(PAUSE), true);
                call(17, null, true);
              }
            } catch (Exception ignored) {
            } finally {
              try {
                if (callback != null && remote != null) call(15, callback, true);
              } catch (Exception ignored) {
              }
              remote = null;
              callback = null;
            }
          }
        });
  }
}
