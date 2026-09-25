package io.github.yuntumusic.direct;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import java.io.*;
import java.net.*;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

/** HTTPS only, bounded responses, no credentials sent to image/audio hosts. */
class NativeTransport {
  static final String UA =
      "NeteaseMusic/9.5.61.260802021928(9005061);Dalvik/2.1.0 (Linux; U; Android 12; HBN-AL00"
          + " Build/cd737a2.0)";
  final Context context;
  final String device;
  private JSONObject key;
  private long keyAt;
  private final Object keyLock = new Object();

  NativeTransport(Context c) {
    context = c;
    android.content.SharedPreferences p = c.getSharedPreferences("native-session", 0);
    String d = p.getString("device", "");
    if (d.length() == 0) {
      d = NativeCrypto.hex(NativeCrypto.random(16));
      p.edit().putString("device", d).commit();
    }
    device = d;
  }

  static final class Reply {
    final JSONObject body;
    final String cookie;

    Reply(JSONObject b, String c) {
      body = b;
      cookie = c;
    }
  }

  static Map<String, String> cookies(String s) {
    Map<String, String> out = new LinkedHashMap<String, String>();
    for (String part : s.split(";")) {
      int i = part.indexOf('=');
      if (i > 0) out.put(part.substring(0, i).trim(), part.substring(i + 1).trim());
    }
    return out;
  }

  static String cookieHeader(Map<String, String> map) {
    StringBuilder out = new StringBuilder();
    for (Map.Entry<String, String> e : map.entrySet()) {
      if (out.length() > 0) out.append("; ");
      out.append(e.getKey()).append('=').append(e.getValue());
    }
    return out.toString();
  }

  Reply call(
      String uri, JSONObject params, String mode, String session, int timeout, boolean mutation)
      throws Exception {
    JSONObject data = new JSONObject(params.toString());
    data.put("e_r", false);
    Map<String, String> cookie = cookies(session), headers = new LinkedHashMap<String, String>();
    cookie.put("deviceId", device);
    cookie.put("os", "pc");
    cookie.put("appver", "3.1.17.204416");
    cookie.put("osver", "Microsoft-Windows-10-Professional-build-19045-64bit");
    cookie.put("channel", "netease");
    cookie.put("__remember_me", "true");
    cookie.put("ntes_kaola_ad", "1");
    cookie.put("_ntes_nuid", device);
    cookie.put("_ntes_nnid", device + "," + System.currentTimeMillis());
    cookie.put("WEVNSM", "1.0.0");
    cookie.put("WNMCID", device.substring(0, 6) + "." + System.currentTimeMillis() + ".01.0");
    if (uri.indexOf("login") == -1) cookie.put("NMTID", NativeCrypto.hex(NativeCrypto.random(16)));
    String url;
    JSONObject form;
    if (mode.equals("weapi")) {
      headers.put("Referer", "https://music.163.com");
      headers.put(
          "User-Agent",
          "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/124.0.0.0"
              + " Safari/537.36");
      data.put("csrf_token", cookie.containsKey("__csrf") ? cookie.get("__csrf") : "");
      form = NativeCrypto.weapi(data);
      url = "https://music.163.com/weapi/" + uri.substring(5);
    } else if (mode.equals("xeapi")) {
      JSONObject state = publicKey(timeout);
      String build = String.valueOf(System.currentTimeMillis() / 1000);
      cookie.put("os", "android");
      cookie.put("appver", "9.1.65");
      cookie.put("osver", "16");
      cookie.put("buildver", build);
      cookie.put("sDeviceId", device);
      headers.put("User-Agent", UA);
      headers.put("X-Client-Enc-State", "ENCRYPTED");
      headers.put("x-aeapi", "true");
      headers.put("x-deviceid", device);
      headers.put("x-sdeviceid", device);
      headers.put("x-os", "android");
      headers.put("x-osver", "16");
      headers.put("x-appver", "9.1.65");
      headers.put("x-buildver", build);
      if (cookie.containsKey("MUSIC_U")) headers.put("x-music-u", cookie.get("MUSIC_U"));
      form = NativeCrypto.xeapi(uri, data, state);
      url = "https://interface3.music.163.com/xeapi/" + uri.substring(5);
    } else {
      JSONObject header = new JSONObject();
      for (String name :
          new String[] {"osver", "deviceId", "os", "appver", "channel", "MUSIC_U", "MUSIC_A"})
        if (cookie.containsKey(name)) header.put(name, cookie.get(name));
      header
          .put("versioncode", "140")
          .put("mobilename", "")
          .put("buildver", String.valueOf(System.currentTimeMillis() / 1000))
          .put("resolution", "1920x1080")
          .put("__csrf", cookie.containsKey("__csrf") ? cookie.get("__csrf") : "")
          .put(
              "requestId",
              System.currentTimeMillis()
                  + "_"
                  + String.format(Locale.US, "%04d", NativeCrypto.RANDOM.nextInt(1000)));
      cookie.clear();
      Iterator<String> names = header.keys();
      while (names.hasNext()) {
        String n = names.next();
        cookie.put(n, Api.encode(header.getString(n)).replace("+", "%20"));
      }
      data.put("header", header);
      headers.put("User-Agent", "NeteaseMusic 9.0.90/5038 (iPhone; iOS 16.2; zh_CN)");
      form = NativeCrypto.eapi(uri, data);
      url = "https://interfacepc.music.163.com/eapi/" + uri.substring(5);
    }
    headers.put("Cookie", cookieHeader(cookie));
    return post(url, NativeCrypto.form(form), headers, mode.equals("xeapi"), timeout, !mutation);
  }

  private JSONObject publicKey(int timeout) throws Exception {
    synchronized (keyLock) {
      if (key != null && SystemClock.elapsedRealtime() - keyAt < 3600000) return key;
      StringBuilder nonce = new StringBuilder();
      for (int i = 0; i < 16; i++) nonce.append(NativeCrypto.RANDOM.nextInt(10));
      String n = nonce.toString(), time = String.valueOf(System.currentTimeMillis());
      JSONObject data =
          new JSONObject()
              .put("appVersion", "9.5.61")
              .put("currentKeyVersion", "")
              .put("deviceId", device)
              .put("nonce", n)
              .put("os", "android")
              .put("requestType", "active")
              .put("signature", NativeCrypto.sign(time, n))
              .put("t1", "")
              .put("t2", "")
              .put("timestamp", time)
              .put("uid", "");
      Map<String, String> headers = new LinkedHashMap<String, String>();
      headers.put("User-Agent", UA);
      headers.put("Cookie", "deviceId=" + device);
      Reply r =
          post(
              "https://interface.music.163.com/api/gorilla/anti/crawler/security/key/get",
              NativeCrypto.form(data),
              headers,
              false,
              timeout,
              true);
      JSONObject d = r.body.optJSONObject("data");
      if (r.body.optInt("code") != 200 || d == null) throw new IOException("网易云加密初始化失败，请稍后重试");
      String expected = NativeCrypto.sign(d.getString("timestamp"), n);
      if (!java.security.MessageDigest.isEqual(
          NativeCrypto.bytes(expected), NativeCrypto.bytes(d.optString("signature"))))
        throw new IOException("网易云加密公钥校验失败");
      JSONObject state =
          new JSONObject(
              NativeCrypto.text(
                  NativeCrypto.aes(
                      NativeCrypto.un64(d.getString("encryptedData")),
                      NativeCrypto.XEAPI,
                      null,
                      false)));
      if (state.optString("sk").length() == 0
          || NativeCrypto.un64(state.getString("publicKey")).length != 32)
        throw new IOException("网易云加密公钥不完整");
      state.getString("version");
      key = state;
      keyAt = SystemClock.elapsedRealtime();
      return key;
    }
  }

  Reply post(
      String address,
      String form,
      Map<String, String> headers,
      boolean encrypted,
      int timeout,
      boolean retryRead)
      throws Exception {
    long deadline = SystemClock.elapsedRealtime() + timeout;
    for (int attempt = 0; ; attempt++) {
      HttpsURLConnection conn = null;
      int status = -1;
      long start = SystemClock.elapsedRealtime();
      try {
        int remaining = (int) (deadline - SystemClock.elapsedRealtime());
        if (remaining <= 0) throw new SocketTimeoutException();
        conn =
            (HttpsURLConnection)
                new URL(address + "?_t=" + System.currentTimeMillis()).openConnection();
        conn.setSSLSocketFactory(Api.tls(context));
        conn.setConnectTimeout(Math.min(10000, remaining));
        conn.setReadTimeout(remaining);
        conn.setUseCaches(false);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=utf-8");
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Encoding", "gzip");
        for (Map.Entry<String, String> h : headers.entrySet())
          conn.setRequestProperty(h.getKey(), h.getValue());
        if (attempt > 0) conn.setRequestProperty("Connection", "close");
        byte[] encoded = NativeCrypto.bytes(form);
        conn.setFixedLengthStreamingMode(encoded.length);
        OutputStream out = conn.getOutputStream();
        try {
          out.write(encoded);
        } finally {
          out.close();
        }
        remaining = (int) (deadline - SystemClock.elapsedRealtime());
        if (remaining <= 0) throw new SocketTimeoutException();
        conn.setReadTimeout(remaining);
        status = conn.getResponseCode();
        InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) throw new IOException("网易云接口 HTTP " + status);
        byte[] raw;
        try {
          if ("gzip".equalsIgnoreCase(conn.getContentEncoding()))
            in = new java.util.zip.GZIPInputStream(in);
          raw = NativeCrypto.bounded(in);
        } finally {
          in.close();
        }
        JSONObject body;
        try {
          body = NativeCrypto.response(raw, encrypted);
        } catch (Exception e) {
          throw new IOException("网易云响应无法解析（HTTP " + status + "）");
        }
        if (status < 200 || status >= 300) throw NativeApi.fail(body.optInt("code", status));
        Map<String, String> received = new LinkedHashMap<String, String>();
        for (Map.Entry<String, List<String>> h : conn.getHeaderFields().entrySet())
          if ("Set-Cookie".equalsIgnoreCase(h.getKey()))
            for (String v : h.getValue()) received.putAll(cookies(v.split(";", 2)[0]));
        Log.i(
            "YuntuDirect",
            "upstream route="
                + new URL(address).getPath()
                + " status="
                + status
                + " code="
                + body.optInt("code")
                + " duration_ms="
                + (SystemClock.elapsedRealtime() - start));
        return new Reply(body, cookieHeader(received));
      } catch (Exception e) {
        boolean retry =
            retryRead
                && attempt == 0
                && ConnectionFailure.retryable("GET", status, e)
                && deadline - SystemClock.elapsedRealtime() > 750;
        Log.w(
            "YuntuDirect",
            "transport route="
                + new URL(address).getPath()
                + " status="
                + status
                + " reason="
                + ConnectionFailure.reason(e)
                + " types="
                + ConnectionFailure.types(e)
                + " retry="
                + retry);
        if (!retry) throw e;
        Thread.sleep(250);
      } finally {
        if (conn != null) conn.disconnect();
      }
    }
  }
}
