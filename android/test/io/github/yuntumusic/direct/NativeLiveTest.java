package io.github.yuntumusic.direct;

import android.app.*;
import android.os.*;
import java.util.*;
import org.json.*;

/** Anonymous read-only upstream smoke; no account credentials or mutations. */
public final class NativeLiveTest extends Instrumentation {
  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  public void onStart() {
    Bundle out = new Bundle();
    ArrayList<String> passed = new ArrayList<String>();
    try {
      NativeApi api = new NativeApi(getTargetContext(), new NativeTransport(getTargetContext()));
      JSONObject qr = api.request("POST", "/v1/login/qr");
      if (qr.getString("image").length() < 100) throw new AssertionError("QR image");
      passed.add("live QR key + local image");
      JSONObject poll =
          api.request("GET", "/v1/login/qr/check?id=" + Api.encode(qr.getString("id")));
      if (poll.getInt("status") != 801)
        throw new AssertionError("QR status " + poll.getInt("status"));
      passed.add("live QR poll waiting for scan");
      JSONObject songs = api.request("GET", "/v1/search?q=" + Api.encode("两只老虎") + "&type=song");
      JSONArray list = songs.getJSONArray("items");
      if (list.length() == 0) throw new AssertionError("no public search results");
      passed.add("live song search");
      String id = list.getJSONObject(0).getString("id");
      JSONObject lyrics = api.request("GET", "/v1/songs/" + id + "/presentation");
      if (lyrics.getBoolean("lyricError")) throw new AssertionError("lyric failure");
      passed.add("live lyrics and metadata");
      boolean playable = false;
      for (int i = 0; i < Math.min(4, list.length()); i++) {
        id = list.getJSONObject(i).getString("id");
        try {
          JSONObject source = api.request("GET", "/v1/songs/" + id + "/url?quality=standard");
          playable = source.getString("url").length() > 0;
          if (playable) {
            out.putString("playable_song_id", id);
            break;
          }
        } catch (NativeApi.Failure e) {
          if (!"NO_PLAYABLE_SOURCE".equals(e.body.optString("code"))) throw e;
        }
      }
      if (!playable) throw new AssertionError("no anonymous playable source in first four results");
      passed.add("live key registration + xeapi playback URL");
      for (String type : new String[] {"artist", "album", "playlist"}) {
        JSONObject r = api.request("GET", "/v1/search?q=" + Api.encode("王力宏") + "&type=" + type);
        if (r.getJSONArray("items").length() == 0) throw new AssertionError(type + " search empty");
        passed.add("live " + type + " search");
        if (type.equals("playlist")) {
          String pid = r.getJSONArray("items").getJSONObject(0).getString("id");
          JSONObject tracks = api.request("GET", "/v1/playlists/" + pid + "/tracks");
          if (tracks.getJSONArray("items").length() == 0)
            throw new AssertionError("empty playlist");
          passed.add("live playlist trackIds and details");
        }
      }
      out.putString("passed", passed.toString());
      out.putInt("count", passed.size());
      out.putLong("pss_kb", Debug.getPss());
      finish(Activity.RESULT_OK, out);
    } catch (Throwable t) {
      out.putString("passed", passed.toString());
      out.putString("failure", android.util.Log.getStackTraceString(t));
      finish(Activity.RESULT_CANCELED, out);
    }
  }
}
