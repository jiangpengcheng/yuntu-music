package io.github.yuntumusic.direct;

import android.app.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.security.cert.Certificate;
import java.util.*;
import javax.net.ssl.*;
import org.json.*;

public final class NativeNetworkTest extends Instrumentation {
  int calls, closed;
  String mode;
  boolean retriedClose;
  ArrayList<String> passed = new ArrayList<String>();

  void check(String n, boolean b) {
    if (!b) throw new AssertionError(n);
    passed.add(n);
  }

  public void onCreate(Bundle a) {
    super.onCreate(a);
    start();
  }

  final class Fake extends HttpsURLConnection {
    int attempt;
    boolean outputClosed;

    Fake(URL u) {
      super(u);
      attempt = ++calls;
    }

    public void disconnect() {
      closed++;
    }

    public boolean usingProxy() {
      return false;
    }

    public void connect() {}

    public String getCipherSuite() {
      return "TEST";
    }

    public Certificate[] getLocalCertificates() {
      return null;
    }

    public Certificate[] getServerCertificates() {
      return new Certificate[0];
    }

    public OutputStream getOutputStream() {
      return new ByteArrayOutputStream() {
        public void close() {
          outputClosed = true;
        }
      };
    }

    public int getResponseCode() throws IOException {
      if (mode.equals("transient") && attempt == 1) {
        SSLHandshakeException e = new SSLHandshakeException("closed");
        e.initCause(new EOFException());
        throw e;
      }
      if (mode.equals("certificate")) throw new SSLPeerUnverifiedException("test certificate");
      if (mode.equals("http")) return 503;
      return 200;
    }

    public InputStream getInputStream() {
      if (attempt > 1) retriedClose = "close".equals(getRequestProperty("Connection"));
      return new ByteArrayInputStream(
          NativeCrypto.bytes(mode.equals("oversize") ? "" : "{\"code\":200}")) {
        int readBytes;

        public int read(byte[] b, int offset, int count) {
          if (!mode.equals("oversize")) return super.read(b, offset, count);
          if (readBytes > 3 * 1024 * 1024) return -1;
          Arrays.fill(b, offset, offset + count, (byte) ' ');
          readBytes += count;
          return count;
        }
      };
    }

    public InputStream getErrorStream() {
      return new ByteArrayInputStream(NativeCrypto.bytes("{\"code\":503}"));
    }

    public Map<String, List<String>> getHeaderFields() {
      return new HashMap<String, List<String>>();
    }
  }

  void setup(String m) {
    mode = m;
    calls = closed = 0;
    retriedClose = false;
  }

  public void onStart() {
    Bundle out = new Bundle();
    try {
      URL.setURLStreamHandlerFactory(
          new URLStreamHandlerFactory() {
            public URLStreamHandler createURLStreamHandler(String protocol) {
              if (!protocol.equals("https")) return null;
              return new URLStreamHandler() {
                protected URLConnection openConnection(URL url) {
                  return new Fake(url);
                }
              };
            }
          });
      NativeTransport t = new NativeTransport(getTargetContext());
      Map<String, String> headers = new HashMap<String, String>();
      setup("transient");
      t.post("https://music.163.com/test", "a=1", headers, false, 5000, true);
      check("read retried once", calls == 2);
      check("retry connection close", retriedClose);
      check("connections disconnected", closed == 2);
      setup("transient");
      try {
        t.post("https://music.163.com/test", "a=1", headers, false, 5000, false);
        throw new AssertionError("write retry");
      } catch (SSLHandshakeException e) {
        check("mutations never replayed", calls == 1 && closed == 1);
      }
      setup("certificate");
      try {
        t.post("https://music.163.com/test", "", headers, false, 5000, true);
        throw new AssertionError("certificate");
      } catch (SSLPeerUnverifiedException e) {
        check("certificate failure never retried", calls == 1);
      }
      setup("http");
      try {
        t.post("https://music.163.com/test", "", headers, false, 5000, true);
        throw new AssertionError("http");
      } catch (NativeApi.Failure e) {
        check("HTTP error not retried", calls == 1 && e.code == 503);
      }
      setup("oversize");
      try {
        t.post("https://music.163.com/test", "", headers, false, 5000, true);
        throw new AssertionError("size");
      } catch (IOException e) {
        check("response bounded to 2 MB", calls == 1 && closed == 1);
      }
      out.putInt("count", passed.size());
      out.putString("passed", passed.toString());
      finish(Activity.RESULT_OK, out);
    } catch (Throwable t) {
      out.putString("failure", android.util.Log.getStackTraceString(t));
      out.putString("passed", passed.toString());
      finish(Activity.RESULT_CANCELED, out);
    }
  }
}
