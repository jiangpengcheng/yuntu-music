package io.github.yuntumusic.direct;

import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.SystemClock;
import io.nayuki.qrcodegen.QrCode;
import java.io.*;
import java.util.*;
import java.util.regex.*;
import org.json.*;

/** In-process replacement for the gateway's /v1 contract; no local HTTP listener. */
final class NativeApi {
  private static NativeApi instance;

  static synchronized NativeApi get(Context c) {
    if (instance == null) instance = new NativeApi(c, new NativeTransport(c));
    return instance;
  }

  final SharedPreferences prefs;
  final NativeTransport transport;
  private final Object lock = new Object();
  private long epoch, likeRevision;
  private String pendingId = "", pendingKey = "";
  private long pendingExpires;
  private Set<String> liked;
  private long likedAt, likedEpoch;
  private JSONArray playlistIds;
  private String playlistId = "";
  private long playlistAt, playlistEpoch;

  NativeApi(Context c, NativeTransport t) {
    prefs = c.getSharedPreferences("native-session", 0);
    transport = t;
  }

  static final class Failure extends IOException {
    final JSONObject body;
    final int code;

    Failure(int c, String message, String errorCode) {
      super(message);
      code = c;
      JSONObject b = new JSONObject();
      try {
        b.put("error", message);
        if (errorCode != null) b.put("code", errorCode);
      } catch (JSONException ignored) {
      }
      body = b;
    }
  }

  static Failure fail(int code) {
    return new Failure(
        code,
        code == 301 || code == 401
            ? "登录已失效，请重新扫码"
            : code == 460
                ? "网易云风控拒绝了请求（460），请稍后重试"
                : code == 512 ? "未能收藏，歌曲可能已在歌单中" : "网易云接口返回异常（" + code + "），请稍后重试",
        null);
  }

  static JSONObject obj(Object... args) throws JSONException {
    JSONObject o = new JSONObject();
    for (int i = 0; i < args.length; i += 2) o.put(String.valueOf(args[i]), args[i + 1]);
    return o;
  }

  static JSONObject object(JSONObject o, String key) {
    JSONObject r = o == null ? null : o.optJSONObject(key);
    return r == null ? new JSONObject() : r;
  }

  static JSONArray array(JSONObject o, String key) {
    JSONArray r = o == null ? null : o.optJSONArray(key);
    return r == null ? new JSONArray() : r;
  }

  static String str(JSONObject o, String key) {
    return o == null || o.isNull(key) ? "" : o.optString(key);
  }

  static int number(Uri u, String name, int def) {
    try {
      return Math.max(0, Integer.parseInt(u.getQueryParameter(name)));
    } catch (Exception e) {
      return def;
    }
  }

  private static final class Session {
    final String cookie;
    final long generation;

    Session(String c, long e) {
      cookie = c;
      generation = e;
    }
  }

  private Session session() {
    synchronized (lock) {
      return new Session(prefs.getString("cookie", ""), epoch);
    }
  }

  private void current(Session s) throws Failure {
    synchronized (lock) {
      if (s.generation != epoch) throw new Failure(409, "账号或二维码已变更，请重试", null);
    }
  }

  private void require(Session s) throws Failure {
    if (!NativeTransport.cookies(s.cookie).containsKey("MUSIC_U"))
      throw new Failure(401, "请先扫码登录", null);
  }

  private JSONObject call(
      Session s, String path, JSONObject data, String mode, boolean mutation, int timeout)
      throws Exception {
    current(s);
    NativeTransport.Reply r = transport.call(path, data, mode, s.cookie, timeout, mutation);
    current(s);
    int code = r.body.optInt("code", 200);
    if (code != 200) throw fail(code);
    return r.body;
  }

  private JSONObject call(Session s, String path, JSONObject data, String mode) throws Exception {
    return call(s, path, data, mode, false, 12000);
  }

  private JSONObject profile(Session s) throws Exception {
    JSONObject p = call(s, "/api/w/nuser/account/get", obj(), "weapi").optJSONObject("profile");
    if (p == null) throw fail(301);
    return p;
  }

  JSONObject request(String method, String path) throws Exception {
    Uri u = Uri.parse(path);
    String p = u.getPath();
    Session s = session();
    int offset = number(u, "offset", 0);
    if (p.equals("/v1/logout") && method.equals("POST")) {
      synchronized (lock) {
        epoch++;
        pendingId = "";
        pendingKey = "";
        liked = null;
        playlistIds = null;
        if (!prefs.edit().remove("cookie").commit()) throw new IOException("无法清除本地登录状态");
      }
      return obj("ok", true);
    }
    if (p.equals("/v1/login/qr") && method.equals("POST")) return createQr();
    if (p.equals("/v1/login/qr/check")) return checkQr(u.getQueryParameter("id"));
    if (p.equals("/v1/session")) {
      if (s.cookie.length() == 0) return obj("loggedIn", false);
      JSONObject b = call(s, "/api/w/nuser/account/get", obj(), "weapi"),
          profile = b.optJSONObject("profile");
      return obj(
          "loggedIn",
          profile != null,
          "profile",
          profile == null
              ? JSONObject.NULL
              : obj("id", str(profile, "userId"), "nickname", str(profile, "nickname")));
    }
    if (p.equals("/v1/playlists")) {
      require(s);
      String uid = profile(s).getString("userId");
      JSONObject r =
          call(
              s,
              "/api/user/playlist",
              obj("uid", uid, "offset", offset, "limit", 50, "includeVideo", true),
              "weapi");
      JSONArray raw = array(r, "playlist"), items = new JSONArray();
      for (int i = 0; i < raw.length(); i++) {
        JSONObject item = raw.getJSONObject(i);
        boolean edit = uid.equals(str(object(item, "creator"), "userId"));
        if ("true".equals(u.getQueryParameter("editable")) && !edit) continue;
        items.put(
            obj(
                "id",
                str(item, "id"),
                "name",
                str(item, "name"),
                "count",
                item.optInt("trackCount"),
                "cover",
                cover(str(item, "coverImgUrl")),
                "editable",
                edit));
      }
      return obj(
          "items",
          items,
          "more",
          r.optBoolean("more"),
          "offset",
          offset,
          "nextOffset",
          offset + raw.length());
    }
    if (p.equals("/v1/search")) return search(s, u, offset);
    if (p.equals("/v1/fm")) return fm(s, Math.min(50, Math.max(1, number(u, "limit", 20))));
    List<String> parts = u.getPathSegments();
    if (parts.size() != 4 || !parts.get(2).matches("[1-9][0-9]{0,14}"))
      throw new IOException("不支持的接口");
    String kind = parts.get(1), id = parts.get(2), action = parts.get(3);
    if (kind.equals("songs")) {
      if (action.equals("url")) return source(s, id, u.getQueryParameter("quality"));
      if (action.equals("presentation")) return presentation(s, id);
      if (action.equals("like")) return like(s, id, method, u.getQueryParameter("value"));
    }
    if (action.equals("tracks")) {
      if (kind.equals("playlists") && method.equals("POST")) {
        require(s);
        String song = u.getQueryParameter("songId");
        if (song == null || !song.matches("[1-9][0-9]{0,14}")) throw new IOException("歌曲 ID 无效");
        String uid = profile(s).getString("userId");
        JSONObject detail =
            call(s, "/api/v6/playlist/detail", obj("id", id, "n", 1, "s", 0), "eapi");
        if (!uid.equals(str(object(object(detail, "playlist"), "creator"), "userId")))
          throw new IOException("只能添加到自己创建的歌单");
        call(
            s,
            "/api/playlist/manipulate/tracks",
            obj(
                "op",
                "add",
                "pid",
                id,
                "trackIds",
                new JSONArray().put(song).toString(),
                "imme",
                "true"),
            "eapi",
            true,
            12000);
        synchronized (lock) {
          playlistIds = null;
        }
        return obj("ok", true);
      }
      if (kind.equals("playlists")) return playlist(s, id, offset);
      if (kind.equals("artists")) {
        JSONObject r =
            call(
                s,
                "/api/v1/artist/songs",
                obj(
                    "id",
                    id,
                    "private_cloud",
                    "true",
                    "work_type",
                    1,
                    "order",
                    "hot",
                    "offset",
                    offset,
                    "limit",
                    100),
                "eapi");
        JSONArray raw = array(r, "songs");
        return obj(
            "items",
            tracks(raw),
            "offset",
            offset,
            "nextOffset",
            offset + raw.length(),
            "more",
            r.optBoolean("more", raw.length() == 100));
      }
      if (kind.equals("albums")) {
        JSONArray all = array(call(s, "/api/v1/album/" + id, obj(), "weapi"), "songs"),
            raw = new JSONArray();
        for (int i = offset; i < Math.min(offset + 100, all.length()); i++) raw.put(all.get(i));
        return obj(
            "items",
            tracks(raw),
            "offset",
            offset,
            "nextOffset",
            offset + raw.length(),
            "more",
            offset + raw.length() < all.length());
      }
    }
    throw new IOException("不支持的接口");
  }

  private JSONObject createQr() throws Exception {
    long generation;
    synchronized (lock) {
      generation = ++epoch;
      pendingId = "";
      pendingKey = "";
    }
    NativeTransport.Reply r =
        transport.call("/api/login/qrcode/unikey", obj("type", 3), "eapi", "", 12000, true);
    if (r.body.optInt("code") != 200) throw fail(r.body.optInt("code"));
    String key = r.body.getString("unikey"),
        url = "https://music.163.com/login?codekey=" + Api.encode(key);
    String image = qrImage(url), id = NativeCrypto.hex(NativeCrypto.random(18));
    long expires = SystemClock.elapsedRealtime() + 180000;
    synchronized (lock) {
      if (generation != epoch) throw new Failure(409, "二维码已更新，请重新打开登录", null);
      pendingId = id;
      pendingKey = key;
      pendingExpires = expires;
    }
    return obj(
        "id", id, "image", image, "url", url, "expiresAt", System.currentTimeMillis() + 180000);
  }

  static String qrImage(String url) throws Exception {
    QrCode qr = QrCode.encodeText(url, QrCode.Ecc.MEDIUM);
    int scale = 5, size = (qr.size + 8) * scale;
    Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
    int[] pixels = new int[size * size];
    Arrays.fill(pixels, 0xffffffff);
    for (int y = 0; y < qr.size; y++)
      for (int x = 0; x < qr.size; x++)
        if (qr.getModule(x, y))
          for (int dy = 0; dy < scale; dy++)
            for (int dx = 0; dx < scale; dx++)
              pixels[((y + 4) * scale + dy) * size + (x + 4) * scale + dx] = 0xff000000;
    bitmap.setPixels(pixels, 0, size, 0, 0, size, size);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
      return "data:image/png;base64," + NativeCrypto.b64(out.toByteArray());
    } finally {
      bitmap.recycle();
    }
  }

  private JSONObject checkQr(String id) throws Exception {
    String key;
    long generation;
    synchronized (lock) {
      if (pendingId.length() == 0
          || !pendingId.equals(id)
          || SystemClock.elapsedRealtime() > pendingExpires)
        return obj("status", 800, "message", "二维码已过期，请刷新");
      key = pendingKey;
      generation = epoch;
    }
    NativeTransport.Reply r =
        transport.call(
            "/api/login/qrcode/client/login", obj("key", key, "type", 3), "eapi", "", 12000, false);
    int status = r.body.optInt("code");
    if (status < 800 || status > 803) throw fail(status);
    synchronized (lock) {
      if (generation != epoch || !pendingId.equals(id))
        return obj("status", 800, "message", "二维码已更新");
      if (status == 803) {
        String cookie = r.cookie;
        String music = NativeTransport.cookies(cookie).get("MUSIC_U");
        if (music == null || music.length() == 0) throw new IOException("授权未返回有效会话，请重新扫码");
        if (!prefs.edit().putString("cookie", cookie).commit())
          throw new IOException("无法保存登录状态，请重试");
        pendingId = "";
        pendingKey = "";
        epoch++;
        liked = null;
        playlistIds = null;
      }
      if (status == 800) {
        pendingId = "";
        pendingKey = "";
      }
    }
    return obj(
        "status",
        status,
        "message",
        status == 800
            ? "二维码已过期，请刷新"
            : status == 801 ? "请用手机网易云音乐扫码" : status == 802 ? "已扫码，请在手机上确认" : "登录成功");
  }

  private JSONObject like(Session s, String id, String method, String value) throws Exception {
    require(s);
    if (method.equals("POST")) {
      if (!"true".equals(value) && !"false".equals(value)) throw new IOException("必须指定喜欢状态");
      call(
          s,
          "/api/radio/like",
          obj("alg", "itembased", "trackId", id, "like", Boolean.parseBoolean(value), "time", "3"),
          "weapi",
          true,
          12000);
      synchronized (lock) {
        liked = null;
        likeRevision++;
      }
      return obj("liked", Boolean.parseBoolean(value));
    }
    long revision;
    synchronized (lock) {
      if (liked != null
          && likedEpoch == s.generation
          && SystemClock.elapsedRealtime() - likedAt < 60000)
        return obj("liked", liked.contains(id));
      revision = likeRevision;
    }
    String uid = profile(s).getString("userId");
    JSONArray ids = array(call(s, "/api/song/like/get", obj("uid", uid), "eapi"), "ids");
    Set<String> result = new HashSet<String>();
    for (int i = 0; i < ids.length(); i++) result.add(ids.getString(i));
    synchronized (lock) {
      current(s);
      if (revision == likeRevision) {
        liked = result;
        likedEpoch = s.generation;
        likedAt = SystemClock.elapsedRealtime();
      }
    }
    return obj("liked", result.contains(id));
  }

  private JSONObject playlist(Session s, String id, int offset) throws Exception {
    JSONArray ids;
    synchronized (lock) {
      ids =
          id.equals(playlistId)
                  && s.generation == playlistEpoch
                  && SystemClock.elapsedRealtime() - playlistAt < 300000
              ? playlistIds
              : null;
    }
    if (ids == null || offset == 0) {
      JSONObject playlist =
          object(
              call(s, "/api/v6/playlist/detail", obj("id", id, "n", 0, "s", 0), "eapi"),
              "playlist");
      ids = array(playlist, "trackIds");
      synchronized (lock) {
        current(s);
        playlistId = id;
        playlistIds = ids;
        playlistAt = SystemClock.elapsedRealtime();
        playlistEpoch = s.generation;
      }
    }
    JSONArray selected = new JSONArray();
    for (int i = offset; i < Math.min(offset + 100, ids.length()); i++)
      selected.put(obj("id", ids.getJSONObject(i).getLong("id")));
    JSONArray raw =
        selected.length() == 0
            ? new JSONArray()
            : array(call(s, "/api/v3/song/detail", obj("c", selected.toString()), "eapi"), "songs");
    // Follow trackIds order, even if the detail endpoint returns a different order.
    Map<String, JSONObject> byId = new HashMap<String, JSONObject>();
    for (int i = 0; i < raw.length(); i++) {
      JSONObject t = raw.getJSONObject(i);
      byId.put(str(t, "id"), t);
    }
    JSONArray ordered = new JSONArray();
    for (int i = 0; i < selected.length(); i++) {
      JSONObject t = byId.get(selected.getJSONObject(i).getString("id"));
      if (t != null) ordered.put(t);
    }
    int next = offset + selected.length();
    return obj(
        "items",
        tracks(ordered),
        "offset",
        offset,
        "nextOffset",
        next,
        "more",
        next < ids.length());
  }

  private JSONObject search(Session s, Uri u, int offset) throws Exception {
    String query = u.getQueryParameter("q");
    if (query == null || query.trim().length() == 0 || query.length() > 100)
      throw new IOException("请输入 1–100 个字符的搜索内容");
    String kind = u.getQueryParameter("type");
    if (kind == null) kind = "song";
    String key, countKey;
    int type;
    if (kind.equals("song")) {
      key = "songs";
      countKey = "songCount";
      type = 1;
    } else if (kind.equals("artist")) {
      key = "artists";
      countKey = "artistCount";
      type = 100;
    } else if (kind.equals("album")) {
      key = "albums";
      countKey = "albumCount";
      type = 10;
    } else if (kind.equals("playlist")) {
      key = "playlists";
      countKey = "playlistCount";
      type = 1000;
    } else throw new IOException("不支持此搜索类型");
    JSONObject r =
        object(
            call(
                s,
                "/api/cloudsearch/pc",
                obj("s", query.trim(), "type", type, "limit", 50, "offset", offset, "total", true),
                "eapi"),
            "result");
    JSONArray raw = array(r, key), items = new JSONArray();
    if (type == 1) items = tracks(raw);
    else
      for (int i = 0; i < raw.length(); i++) {
        JSONObject t = raw.getJSONObject(i);
        String image = "", sub = "";
        if (type == 100) {
          image = str(t, "picUrl");
          if (image.length() == 0) image = str(t, "img1v1Url");
          sub = t.optInt("musicSize") + " 首歌曲 · " + t.optInt("albumSize") + " 张专辑";
        }
        if (type == 10) {
          image = str(t, "picUrl");
          sub = str(object(t, "artist"), "name");
          if (sub.length() == 0) sub = artists(array(t, "artists"));
          if (t.optInt("size") > 0) sub += " · " + t.optInt("size") + " 首";
        }
        if (type == 1000) {
          image = str(t, "coverImgUrl");
          sub = t.optInt("trackCount") + " 首歌曲";
          String name = str(object(t, "creator"), "nickname");
          if (name.length() > 0) sub += " · " + name;
        }
        items.put(
            obj(
                "id",
                str(t, "id"),
                "name",
                str(t, "name"),
                "cover",
                cover(image),
                "subtitle",
                sub));
      }
    return obj(
        "type",
        kind,
        "items",
        items,
        "offset",
        offset,
        "nextOffset",
        offset + raw.length(),
        "more",
        r.has(countKey) ? offset + raw.length() < r.optInt(countKey) : raw.length() == 50);
  }

  private JSONObject fm(Session s, int limit) throws Exception {
    require(s);
    long deadline = SystemClock.elapsedRealtime() + 16000;
    JSONArray result = new JSONArray();
    Set<String> seen = new HashSet<String>();
    int empty = 0;
    String reason = "";
    for (int tries = 0; tries < 30 && result.length() < limit; tries++) {
      int remaining = (int) (deadline - SystemClock.elapsedRealtime());
      if (remaining < 500) {
        reason = "timeout";
        break;
      }
      JSONArray raw;
      try {
        raw =
            array(
                call(s, "/api/v1/radio/get", obj(), "weapi", false, Math.min(6000, remaining)),
                "data");
      } catch (Exception e) {
        if (result.length() == 0
            || (e instanceof Failure
                && (((Failure) e).code == 301
                    || ((Failure) e).code == 401
                    || ((Failure) e).code == 409))) throw e;
        reason = "upstream";
        break;
      }
      int added = 0;
      for (int i = 0; i < raw.length() && result.length() < limit; i++) {
        JSONObject t = raw.getJSONObject(i);
        String id = str(t, "id");
        if (id.length() > 0 && seen.add(id)) {
          result.put(track(t));
          added++;
        }
      }
      empty = added == 0 ? empty + 1 : 0;
      if (empty >= 2) {
        reason = "duplicates";
        break;
      }
    }
    current(s);
    return obj(
        "items", result, "requested", limit, "partial", result.length() < limit, "reason", reason);
  }

  private JSONObject presentation(Session s, String id) throws Exception {
    JSONObject lyric = null, detail = null;
    boolean error = false;
    try {
      lyric =
          call(
              s,
              "/api/song/lyric",
              obj("id", id, "tv", -1, "lv", -1, "rv", -1, "kv", -1, "_nmclfl", 1),
              "eapi");
    } catch (Exception e) {
      error = true;
    }
    try {
      detail =
          array(
                  call(
                      s,
                      "/api/v3/song/detail",
                      obj("c", new JSONArray().put(obj("id", Long.parseLong(id))).toString()),
                      "weapi"),
                  "songs")
              .optJSONObject(0);
    } catch (Exception ignored) {
    }
    current(s);
    JSONObject out = lyrics(str(object(lyric, "lrc"), "lyric"));
    return out.put("instrumental", lyric != null && lyric.optBoolean("nolyric"))
        .put("lyricError", error)
        .put("cover", cover(str(object(detail, "al"), "picUrl")));
  }

  private JSONObject source(Session s, String id, String quality) throws Exception {
    if (quality == null) quality = "exhigh";
    if (!Arrays.asList("standard", "higher", "exhigh", "lossless").contains(quality))
      throw new IOException("不支持此音质");
    JSONObject song =
        array(
                call(
                    s,
                    "/api/song/enhance/player/url/v1",
                    obj("ids", "[" + id + "]", "level", quality, "encodeType", "flac"),
                    "xeapi"),
                "data")
            .optJSONObject(0);
    if (song == null) throw new IOException("网易云音源响应异常，请重试");
    String url = str(song, "url");
    android.util.Log.i(
        "YuntuDirect",
        "source song="
            + id
            + " has_url="
            + (url.length() > 0)
            + " code="
            + song.optInt("code")
            + " quality="
            + quality);
    if (url.length() == 0)
      throw new Failure(
          403,
          song.optInt("code") == 404 ? "该歌曲版本在网易云暂无可用音源，请尝试其他版本" : "网易云未返回可播放音源，请确认账号权限或稍后重试",
          "NO_PLAYABLE_SOURCE");
    Uri parsed = Uri.parse(url);
    if (!"https".equals(parsed.getScheme()) && !"http".equals(parsed.getScheme()))
      throw new IOException("音源协议无效");
    return obj(
        "url",
        url,
        "quality",
        song.optString("level", quality),
        "format",
        str(song, "type"),
        "bitrate",
        song.optInt("br"),
        "trial",
        !song.isNull("freeTrialInfo") && !"null".equals(str(song, "freeTrialInfo")),
        "expiresIn",
        song.optInt("expi", 1200));
  }

  static String artists(JSONArray a) {
    StringBuilder s = new StringBuilder();
    for (int i = 0; i < a.length(); i++) {
      if (s.length() > 0) s.append(" / ");
      s.append(str(a.optJSONObject(i), "name"));
    }
    return s.toString();
  }

  static JSONObject track(JSONObject t) throws Exception {
    JSONObject album = t.optJSONObject("al");
    if (album == null) album = object(t, "album");
    JSONArray ar = t.optJSONArray("ar");
    if (ar == null) ar = array(t, "artists");
    return obj(
        "id",
        str(t, "id"),
        "name",
        t.optString("name", "未知歌曲"),
        "artist",
        artists(ar),
        "album",
        str(album, "name"),
        "cover",
        cover(str(album, "picUrl")),
        "duration",
        t.optLong("dt", t.optLong("duration")));
  }

  static JSONArray tracks(JSONArray raw) throws Exception {
    JSONArray out = new JSONArray();
    for (int i = 0; i < raw.length(); i++) out.put(track(raw.getJSONObject(i)));
    return out;
  }

  static String cover(String raw) {
    try {
      Uri u = Uri.parse(raw);
      if (u.getHost() == null
          || u.getUserInfo() != null
          || (!"http".equals(u.getScheme()) && !"https".equals(u.getScheme()))) return "";
      Uri.Builder b = u.buildUpon().scheme("https").clearQuery();
      for (String key : u.getQueryParameterNames())
        if (!key.equals("param"))
          for (String v : u.getQueryParameters(key)) b.appendQueryParameter(key, v);
      return b.appendQueryParameter("param", "480y480").build().toString();
    } catch (Exception e) {
      return "";
    }
  }

  static JSONObject lyrics(String text) throws Exception {
    if (text.length() > 200000) text = text.substring(0, 200000);
    Matcher offset =
        Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\]", Pattern.CASE_INSENSITIVE).matcher(text);
    long shift = 0;
    try {
      if (offset.find()) shift = Long.parseLong(offset.group(1));
    } catch (Exception ignored) {
    }
    Pattern times = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");
    TreeMap<Long, String> lines = new TreeMap<Long, String>();
    int count = 0;
    for (String raw : text.split("\\r?\\n")) {
      String words = raw.replaceAll("\\[[^\\]]*\\]", "").trim();
      if (words.length() > 500) words = words.substring(0, 500);
      if (words.length() == 0) continue;
      Matcher m = times.matcher(raw);
      while (m.find() && count < 2000) {
        int seconds = Integer.parseInt(m.group(2));
        if (seconds >= 60) continue;
        String fraction = m.group(3) == null ? "" : m.group(3);
        fraction = (fraction + "000").substring(0, 3);
        long at =
            Math.max(
                0,
                Integer.parseInt(m.group(1)) * 60000L
                    + seconds * 1000L
                    + Integer.parseInt(fraction)
                    + shift);
        String old = lines.get(at),
            value = old == null || old.equals(words) ? words : old + " / " + words;
        lines.put(at, value.length() > 500 ? value.substring(0, 500) : value);
        count++;
      }
      if (count >= 2000) break;
    }
    JSONArray out = new JSONArray();
    for (Map.Entry<Long, String> line : lines.entrySet())
      out.put(obj("time", line.getKey(), "text", line.getValue()));
    String plain = out.length() == 0 ? text.replaceAll("\\[[^\\]]*\\]", "").trim() : "";
    return obj("lines", out, "plain", plain.length() > 20000 ? plain.substring(0, 20000) : plain);
  }
}
