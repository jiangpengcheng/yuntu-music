package io.github.yuntumusic.direct;

import android.content.*;
import android.os.*;
import android.util.Log;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.*;
import org.json.*;

public final class Api {
  public interface Callback {
    void done(JSONObject data, String error);
  }

  private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static SSLSocketFactory backendTls;
  private static final AtomicLong REQUEST_IDS = new AtomicLong();

  static synchronized SSLSocketFactory tls(Context context) throws Exception {
    if (backendTls == null) backendTls = new Tls12(context.getApplicationContext());
    return backendTls;
  }

  public static android.content.SharedPreferences prefs(Context c) {
    return c.getSharedPreferences("settings", 0);
  }

  public static int fmCount(Context c) {
    int count = prefs(c).getInt("fmCount", 20);
    return count == 10 || count == 20 || count == 30 || count == 50 ? count : 20;
  }

  public static String encode(String s) {
    try {
      return URLEncoder.encode(s, "UTF-8");
    } catch (Exception e) {
      return "";
    }
  }

  public static void request(
      final Context c, final String method, final String path, final Callback callback) {
    final Context app = c.getApplicationContext();
    final long id = REQUEST_IDS.incrementAndGet();
    POOL.execute(
        new Runnable() {
          public void run() {
            JSONObject data = null;
            String error = null;
            long start = SystemClock.elapsedRealtime();
            try {
              data = NativeApi.get(app).request(method, path);
            } catch (NativeApi.Failure e) {
              error = e.getMessage();
              data = e.body;
            } catch (Exception e) {
              error = ConnectionFailure.message(e);
            }
            Log.i(
                "YuntuDirect",
                "request id="
                    + id
                    + " route="
                    + ConnectionFailure.route(path)
                    + " ok="
                    + (error == null)
                    + " duration_ms="
                    + (SystemClock.elapsedRealtime() - start));
            final JSONObject result = data;
            final String message = error;
            MAIN.post(
                new Runnable() {
                  public void run() {
                    callback.done(result, message);
                  }
                });
          }
        });
  }

  // Enable TLS 1.2 on KitKat; extend public CA trust while retaining hostname checks.
  private static final class Tls12 extends SSLSocketFactory {
    private final SSLSocketFactory delegate;

    Tls12(Context context) throws Exception {
      SSLContext c = SSLContext.getInstance("TLS");
      InputStream pem = context.getResources().openRawResource(R.raw.isrg_root_x1);
      try {
        c.init(
            null,
            new TrustManager[] {BackendTrust.withExtraRoot(BackendTrust.system(), pem)},
            null);
      } finally {
        pem.close();
      }
      delegate = c.getSocketFactory();
    }

    private Socket tune(Socket s) {
      if (s instanceof SSLSocket) {
        SSLSocket ssl = (SSLSocket) s;
        List<String> enabled = new ArrayList<String>();
        for (String p : ssl.getSupportedProtocols())
          if (p.equals("TLSv1.2") || p.equals("TLSv1.3")) enabled.add(p);
        if (!enabled.isEmpty())
          ssl.setEnabledProtocols(enabled.toArray(new String[enabled.size()]));
      }
      return s;
    }

    public String[] getDefaultCipherSuites() {
      return delegate.getDefaultCipherSuites();
    }

    public String[] getSupportedCipherSuites() {
      return delegate.getSupportedCipherSuites();
    }

    public Socket createSocket(Socket s, String h, int p, boolean close) throws IOException {
      return tune(delegate.createSocket(s, h, p, close));
    }

    public Socket createSocket(String h, int p) throws IOException {
      return tune(delegate.createSocket(h, p));
    }

    public Socket createSocket(String h, int p, InetAddress l, int lp) throws IOException {
      return tune(delegate.createSocket(h, p, l, lp));
    }

    public Socket createSocket(InetAddress h, int p) throws IOException {
      return tune(delegate.createSocket(h, p));
    }

    public Socket createSocket(InetAddress h, int p, InetAddress l, int lp) throws IOException {
      return tune(delegate.createSocket(h, p, l, lp));
    }
  }
}
