package io.github.yuntumusic.direct;

import android.content.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import org.json.*;

/** Instrumentation-only fixture; absent from release APK. */
final class FixtureTransport extends NativeTransport {
  FixtureTransport(Context c) {
    super(c);
  }

  static void install(Context c) throws Exception {
    c.getSharedPreferences("native-session", 0)
        .edit()
        .putString("cookie", "MUSIC_U=fixture")
        .commit();
    Field field = NativeApi.class.getDeclaredField("instance");
    field.setAccessible(true);
    field.set(null, new NativeApi(c, new FixtureTransport(c)));
  }

  Reply call(String uri, JSONObject data, String mode, String cookie, int timeout, boolean mutation)
      throws Exception {
    JSONObject request = NativeApi.obj("uri", uri, "data", data);
    HttpURLConnection conn =
        (HttpURLConnection) new URL("http://10.0.2.2:3211/native-test").openConnection();
    conn.setConnectTimeout(5000);
    conn.setReadTimeout(12000);
    conn.setDoOutput(true);
    conn.setRequestMethod("POST");
    byte[] bytes = NativeCrypto.bytes(request.toString());
    conn.setFixedLengthStreamingMode(bytes.length);
    try {
      OutputStream out = conn.getOutputStream();
      out.write(bytes);
      out.close();
      InputStream in = conn.getInputStream();
      JSONObject body;
      try {
        body = new JSONObject(NativeCrypto.text(NativeCrypto.bounded(in)));
      } finally {
        in.close();
      }
      if (body.has("failure")) throw NativeApi.fail(body.getInt("failure"));
      return new Reply(body.getJSONObject("body"), body.optString("cookie"));
    } finally {
      conn.disconnect();
    }
  }
}
