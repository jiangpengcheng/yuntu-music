package io.github.yuntumusic.direct;

import android.content.*;
import android.os.*;
import android.support.v4.media.session.MediaSessionCompat;
import android.util.Log;

/** API 19 MediaBrowser wire client: ask the original player to select Bluetooth and own focus. */
final class OemBluetoothPlayer {
  interface Result {
    void done(boolean accepted);
  }

  private final Context context;
  private final Handler worker;
  private final Handler main = new Handler(Looper.getMainLooper());
  private Messenger server;
  private volatile IBinder session;
  private boolean bound;
  private volatile boolean closed;
  private volatile int playVersion;
  private Result pending;
  private final Runnable timeout =
      new Runnable() {
        public void run() {
          complete(false);
        }
      };
  private final Messenger callbacks =
      new Messenger(
          new Handler(Looper.getMainLooper()) {
            public void handleMessage(Message message) {
              if (closed) return;
              if (message.what == 2) {
                complete(false);
                return;
              }
              if (message.what != 1) return;
              try {
                Bundle data = message.getData();
                data.setClassLoader(MediaSessionCompat.Token.class.getClassLoader());
                MediaSessionCompat.Token token = data.getParcelable("data_media_session_token");
                session = token == null ? null : token.binder;
                if (pending != null) dispatch();
              } catch (RuntimeException e) {
                complete(false);
              }
            }
          });
  private final ServiceConnection connection =
      new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
          if (closed) return;
          server = new Messenger(binder);
          final Messenger target = server;
          worker.post(
              new Runnable() {
                public void run() {
                  try {
                    Bundle data = new Bundle();
                    data.putString("data_package_name", context.getPackageName());
                    data.putBundle("data_root_hints", null);
                    send(target, 1, data);
                  } catch (RemoteException e) {
                    main.post(timeout);
                  }
                }
              });
        }

        public void onServiceDisconnected(ComponentName name) {
          session = null;
          server = null;
          complete(false);
        }
      };

  OemBluetoothPlayer(Context context, Handler worker) {
    this.context = context;
    this.worker = worker;
  }

  void open() {
    Intent intent =
        new Intent("android.media.browse.MediaBrowserService")
            .setComponent(
                new ComponentName(
                    "com.ecarx.multimedia",
                    "com.ecarx.multimedia.modules.service.MediaPlayService"));
    try {
      bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
    } catch (RuntimeException e) {
      bound = false;
    }
  }

  boolean available() {
    return bound && !closed;
  }

  void play(Result result) {
    if (closed || !bound) {
      result.done(false);
      return;
    }
    if (pending != null) return; // Coalesce repeated taps during service connection.
    playVersion++;
    pending = result;
    if (session != null) dispatch();
    else main.postDelayed(timeout, 2500);
  }

  private void dispatch() {
    main.removeCallbacks(timeout);
    final IBinder target = session;
    final int ticket = playVersion;
    worker.post(
        new Runnable() {
          public void run() {
            if (closed || ticket != playVersion) return;
            boolean accepted = false;
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
              if (target != null
                  && "android.support.v4.media.session.IMediaSession"
                      .equals(target.getInterfaceDescriptor())) {
                data.writeInterfaceToken("android.support.v4.media.session.IMediaSession");
                data.writeString("CUSTOM_PLAY_BT_ACTION");
                data.writeInt(0); // Null extras Bundle.
                accepted = target.transact(26, data, reply, 0);
                if (accepted) reply.readException();
              }
            } catch (Exception e) {
              Log.w("YuntuBluetooth", "oem_play_failed type=" + e.getClass().getSimpleName());
              accepted = false;
            } finally {
              data.recycle();
              reply.recycle();
            }
            final boolean ok = accepted;
            main.post(
                new Runnable() {
                  public void run() {
                    if (ticket == playVersion) complete(ok);
                  }
                });
          }
        });
  }

  private void complete(boolean accepted) {
    main.removeCallbacks(timeout);
    Result result = pending;
    pending = null;
    if (!closed && result != null) result.done(accepted);
  }

  private void send(Messenger target, int what, Bundle data) throws RemoteException {
    Message message = Message.obtain();
    message.what = what;
    message.arg1 = 1;
    message.replyTo = callbacks;
    if (data != null) message.setData(data);
    target.send(message);
  }

  void cancelPlay() {
    playVersion++;
    pending = null;
    main.removeCallbacks(timeout);
  }

  void close() {
    cancelPlay();
    closed = true;
    pending = null;
    main.removeCallbacksAndMessages(null);
    final Messenger target = server;
    session = null;
    server = null;
    if (bound) {
      try {
        context.unbindService(connection);
      } catch (RuntimeException ignored) {
      }
      bound = false;
    }
    if (target != null)
      worker.post(
          new Runnable() {
            public void run() {
              try {
                send(target, 2, null);
              } catch (RemoteException ignored) {
              }
            }
          });
  }
}
