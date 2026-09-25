package io.github.yuntumusic.direct;

import java.util.*;
import org.json.*;

public final class Track {
  public final String id, name, artist, album, cover;
  public final int duration;

  public Track(JSONObject o) {
    id = o.optString("id");
    name = o.optString("name");
    artist = o.optString("artist");
    album = o.optString("album");
    cover = o.optString("cover");
    duration = o.optInt("duration");
  }

  public JSONObject json() {
    JSONObject o = new JSONObject();
    try {
      o.put("id", id);
      o.put("name", name);
      o.put("artist", artist);
      o.put("album", album);
      o.put("cover", cover);
      o.put("duration", duration);
    } catch (JSONException ignored) {
    }
    return o;
  }

  public static ArrayList<Track> parse(JSONArray a) throws JSONException {
    ArrayList<Track> out = new ArrayList<Track>();
    if (a != null) for (int i = 0; i < a.length(); i++) out.add(new Track(a.getJSONObject(i)));
    return out;
  }
}
