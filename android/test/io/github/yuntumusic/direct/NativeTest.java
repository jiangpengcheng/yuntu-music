package io.github.yuntumusic.direct;

import android.app.*;
import android.content.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class NativeTest extends Instrumentation {
  ArrayList<String> passed = new ArrayList<String>();

  void check(String name, boolean ok) {
    if (!ok) throw new AssertionError(name);
    passed.add(name);
  }

  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  final class Fake extends NativeTransport {
    int details, fm, mutationCalls;
    boolean noSource, mutationFails;
    CountDownLatch entered, release;

    Fake(Context c) {
      super(c);
    }

    Reply reply(JSONObject b) throws Exception {
      return new Reply(b, "");
    }

    Reply call(
        String uri, JSONObject data, String mode, String cookie, int timeout, boolean mutation)
        throws Exception {
      if (uri.equals("/api/login/qrcode/unikey"))
        return reply(NativeApi.obj("code", 200, "unikey", "synthetic-key"));
      if (uri.equals("/api/login/qrcode/client/login")) {
        if (entered != null) {
          entered.countDown();
          release.await(10, TimeUnit.SECONDS);
        }
        return new Reply(NativeApi.obj("code", 803), "MUSIC_U=synthetic-test-session; __csrf=test");
      }
      if (uri.equals("/api/w/nuser/account/get"))
        return reply(
            NativeApi.obj(
                "code", 200, "profile", NativeApi.obj("userId", 7, "nickname", "fixture")));
      if (uri.equals("/api/song/like/get"))
        return reply(NativeApi.obj("code", 200, "ids", new JSONArray().put(1).put(2)));
      if (uri.equals("/api/radio/like") || uri.equals("/api/playlist/manipulate/tracks")) {
        mutationCalls++;
        if (mutationFails) throw NativeApi.fail(512);
        return reply(NativeApi.obj("code", 200));
      }
      if (uri.equals("/api/v6/playlist/detail")) {
        details++;
        JSONArray ids = new JSONArray();
        for (int i = 1; i <= 345; i++) ids.put(NativeApi.obj("id", i));
        return reply(
            NativeApi.obj(
                "code",
                200,
                "playlist",
                NativeApi.obj("creator", NativeApi.obj("userId", 7), "trackIds", ids)));
      }
      if (uri.equals("/api/v3/song/detail")) {
        JSONArray ids = new JSONArray(data.getString("c")), songs = new JSONArray();
        for (int i = ids.length() - 1; i >= 0; i--)
          songs.put(song(ids.getJSONObject(i).getLong("id")));
        return reply(NativeApi.obj("code", 200, "songs", songs));
      }
      if (uri.equals("/api/v1/radio/get")) {
        JSONArray songs = new JSONArray();
        for (int i = 1; i <= 3; i++) songs.put(song(i));
        fm++;
        return reply(NativeApi.obj("code", 200, "data", songs));
      }
      if (uri.equals("/api/song/lyric"))
        return reply(
            NativeApi.obj("code", 200, "lrc", NativeApi.obj("lyric", "[00:01.0]第一行\n[00:02]第二行")));
      if (uri.equals("/api/song/enhance/player/url/v1"))
        return reply(
            NativeApi.obj(
                "code",
                200,
                "data",
                new JSONArray()
                    .put(
                        NativeApi.obj(
                            "id",
                            1,
                            "code",
                            noSource ? 404 : 200,
                            "url",
                            noSource ? JSONObject.NULL : "https://example.test/music.mp3",
                            "freeTrialInfo",
                            JSONObject.NULL,
                            "br",
                            128000))));
      if (uri.equals("/api/cloudsearch/pc")) {
        int type = data.getInt("type");
        String name =
            type == 1 ? "songs" : type == 100 ? "artists" : type == 10 ? "albums" : "playlists";
        return reply(
            NativeApi.obj(
                "code", 200, "result", NativeApi.obj(name, new JSONArray().put(song(1)))));
      }
      if (uri.equals("/api/user/playlist"))
        return reply(
            NativeApi.obj(
                "code",
                200,
                "playlist",
                new JSONArray()
                    .put(
                        NativeApi.obj(
                            "id", 10, "name", "own", "creator", NativeApi.obj("userId", 7)))
                    .put(NativeApi.obj("id", 11, "creator", NativeApi.obj("userId", 8))),
                "more",
                true));
      if (uri.startsWith("/api/v1/album/") || uri.equals("/api/v1/artist/songs"))
        return reply(
            NativeApi.obj("code", 200, "songs", new JSONArray().put(song(1)), "more", false));
      throw new AssertionError(uri);
    }
  }

  JSONObject song(long id) throws Exception {
    return NativeApi.obj(
        "id",
        id,
        "name",
        "song " + id,
        "ar",
        new JSONArray().put(NativeApi.obj("name", "artist")),
        "al",
        NativeApi.obj("name", "album", "picUrl", "https://p1.music.126.net/test.jpg"),
        "dt",
        180000);
  }

  public void onStart() {
    Bundle result = new Bundle();
    try {
      Context c = getTargetContext();
      c.getSharedPreferences("native-session", 0).edit().clear().commit();
      JSONObject vectors = new JSONObject(Vectors.JSON);
      check(
          "eapi matches pinned Node implementation",
          NativeCrypto.eapi("/api/test", NativeApi.obj("id", 123, "text", "中文"))
              .getString("params")
              .equals(vectors.getString("eapi")));
      check(
          "registration HMAC matches Node",
          NativeCrypto.sign("1234567890", "0123456789012345").equals(vectors.getString("sign")));
      check(
          "GCM NIST empty plaintext",
          NativeCrypto.hex(NativeCrypto.gcm(new byte[16], new byte[12], new byte[0]))
              .equals("58e2fccefa7e3061367f1d57a4e7455a"));
      check(
          "encrypted gzip response",
          NativeCrypto.response(NativeCrypto.un64(vectors.getString("response")), true)
                  .getJSONArray("data")
                  .getJSONObject(0)
                  .getInt("id")
              == 123);
      check(
          "encrypted plain response",
          NativeCrypto.response(NativeCrypto.un64(vectors.getString("responsePlain")), true)
                  .getInt("code")
              == 200);
      check(
          "plain JSON error fallback",
          NativeCrypto.response(NativeCrypto.bytes("{\"code\":301}"), true).getInt("code") == 301);
      check(
          "registration key decrypt",
          new JSONObject(
                  NativeCrypto.text(
                      NativeCrypto.aes(
                          NativeCrypto.un64(vectors.getString("key")),
                          NativeCrypto.XEAPI,
                          null,
                          false)))
              .getString("sk")
              .equals("test-sk"));
      long begin = SystemClock.elapsedRealtime();
      JSONObject xe =
          NativeCrypto.xeapi(
              "/api/test",
              NativeApi.obj("ids", "[123]", "level", "standard", "e_r", false),
              vectors.getJSONObject("state"));
      result.putString("xeapi", xe.toString());
      result.putLong("crypto_first_ms", SystemClock.elapsedRealtime() - begin);
      begin = SystemClock.elapsedRealtime();
      for (int i = 0; i < 10; i++)
        NativeCrypto.xeapi("/api/test", NativeApi.obj("id", i), vectors.getJSONObject("state"));
      result.putLong("crypto_10_ms", SystemClock.elapsedRealtime() - begin);
      check(
          "QR generated locally",
          NativeApi.qrImage("https://music.163.com/login?codekey=test")
              .startsWith("data:image/png;base64,iVBOR"));
      JSONObject lyric =
          NativeApi.lyrics("[offset:-100]\n[00:02][00:01.12]a\n[00:02]b\n[01:99]bad");
      JSONArray lines = lyric.getJSONArray("lines");
      check(
          "LRC sort fraction offset",
          lines.length() == 2 && lines.getJSONObject(0).getLong("time") == 1020);
      check("LRC timestamp merge", lines.getJSONObject(1).getString("text").equals("a / b"));
      check(
          "plain lyrics fallback",
          NativeApi.lyrics("[ar:test]\nhello").getString("plain").equals("hello"));
      check(
          "cover HTTPS upgrade",
          NativeApi.cover("http://p1.music.126.net/x?param=20y20")
              .equals("https://p1.music.126.net/x?param=480y480"));
      check(
          "cover rejects credentials",
          NativeApi.cover("https://user:pass@p1.music.126.net/x").equals(""));
      Fake f = new Fake(c);
      NativeApi api = new NativeApi(c, f);
      check(
          "fresh install has no login", !api.request("GET", "/v1/session").getBoolean("loggedIn"));
      String id = api.request("POST", "/v1/login/qr").getString("id");
      check(
          "wrong QR id rejected",
          api.request("GET", "/v1/login/qr/check?id=wrong").getInt("status") == 800);
      check(
          "QR login saves session",
          api.request("GET", "/v1/login/qr/check?id=" + id).getInt("status") == 803);
      check(
          "session profile",
          api.request("GET", "/v1/session").getJSONObject("profile").getString("id").equals("7"));
      JSONArray all = new JSONArray();
      for (int offset = 0; offset < 345; offset += 100) {
        JSONObject page = api.request("GET", "/v1/playlists/99/tracks?offset=" + offset);
        JSONArray rows = page.getJSONArray("items");
        check(
            "playlist page " + offset,
            rows.length() == Math.min(100, 345 - offset)
                && page.getInt("nextOffset") == Math.min(offset + 100, 345));
        for (int i = 0; i < rows.length(); i++) all.put(rows.get(i));
        check("playlist more " + offset, page.getBoolean("more") == (offset < 300));
      }
      check(
          "playlist keeps 345 source order",
          all.length() == 345
              && all.getJSONObject(0).getString("id").equals("1")
              && all.getJSONObject(344).getString("id").equals("345"));
      check("playlist IDs cached across pages", f.details == 1);
      check("like state", api.request("GET", "/v1/songs/1/like").getBoolean("liked"));
      check(
          "unlike result",
          !api.request("POST", "/v1/songs/1/like?value=false").getBoolean("liked"));
      f.mutationFails = true;
      try {
        api.request("POST", "/v1/songs/1/like?value=true");
        throw new AssertionError("mutation rejected");
      } catch (NativeApi.Failure e) {
        check("mutation failure surfaces", e.code == 512);
      }
      f.mutationFails = false;
      JSONObject lists = api.request("GET", "/v1/playlists?editable=true");
      check(
          "editable playlist filter and offset",
          lists.getJSONArray("items").length() == 1 && lists.getInt("nextOffset") == 2);
      check(
          "collect to own playlist",
          api.request("POST", "/v1/playlists/99/tracks?songId=1").getBoolean("ok"));
      for (String type : new String[] {"song", "artist", "album", "playlist"})
        check(
            "search " + type,
            api.request("GET", "/v1/search?q=test&type=" + type).getJSONArray("items").length()
                == 1);
      check(
          "artist tracks",
          api.request("GET", "/v1/artists/1/tracks").getJSONArray("items").length() == 1);
      check(
          "album tracks",
          api.request("GET", "/v1/albums/1/tracks").getJSONArray("items").length() == 1);
      JSONObject fm = api.request("GET", "/v1/fm?limit=20");
      check(
          "FM duplicates bounded",
          fm.getBoolean("partial") && fm.getJSONArray("items").length() == 3 && f.fm == 3);
      check(
          "presentation lyrics",
          api.request("GET", "/v1/songs/1/presentation").getJSONArray("lines").length() == 2);
      check(
          "source parsed",
          api.request("GET", "/v1/songs/1/url")
              .getString("url")
              .equals("https://example.test/music.mp3"));
      f.noSource = true;
      try {
        api.request("GET", "/v1/songs/1/url");
        throw new AssertionError("source failure");
      } catch (NativeApi.Failure e) {
        check(
            "no source keeps auto-skip contract",
            e.body.getString("code").equals("NO_PLAYABLE_SOURCE"));
      }
      final NativeApi racing = api;
      id = api.request("POST", "/v1/login/qr").getString("id");
      final String poll = id;
      f.entered = new CountDownLatch(1);
      f.release = new CountDownLatch(1);
      final JSONObject[] late = new JSONObject[1];
      final Throwable[] failure = new Throwable[1];
      Thread thread =
          new Thread(
              new Runnable() {
                public void run() {
                  try {
                    late[0] = racing.request("GET", "/v1/login/qr/check?id=" + poll);
                  } catch (Throwable t) {
                    failure[0] = t;
                  }
                }
              });
      thread.start();
      check("QR race entered", f.entered.await(5, TimeUnit.SECONDS));
      api.request("POST", "/v1/logout");
      f.release.countDown();
      thread.join(10000);
      check(
          "logout cannot be undone by old QR",
          failure[0] == null
              && late[0].getInt("status") == 800
              && !api.request("GET", "/v1/session").getBoolean("loggedIn"));
      result.putLong("pss_kb", Debug.getPss());
      result.putString("passed", passed.toString());
      result.putInt("count", passed.size());
      finish(Activity.RESULT_OK, result);
    } catch (Throwable t) {
      result.putString("failure", android.util.Log.getStackTraceString(t));
      result.putString("passed", passed.toString());
      finish(Activity.RESULT_CANCELED, result);
    }
  }
}
