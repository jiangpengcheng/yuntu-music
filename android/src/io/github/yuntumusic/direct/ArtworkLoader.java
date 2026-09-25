package io.github.yuntumusic.direct;

import android.content.Context;
import android.graphics.*;
import android.os.*;
import android.util.LruCache;
import java.io.*;
import java.lang.ref.WeakReference;
import java.net.*;
import java.util.concurrent.*;
import javax.net.ssl.HttpsURLConnection;

/** Public image requests never carry the gateway token or account cookies. */
final class ArtworkLoader {
  private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static final LruCache<String, Bitmap> CACHE =
      new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        protected int sizeOf(String k, Bitmap b) {
          return b.getByteCount();
        }
      };

  static void load(final PlayerViews.Art view, final String url, final int size) {
    final String key = url + ":" + size;
    view.setTag(key);
    view.bitmap(null);
    if (url == null || url.length() == 0) return;
    Bitmap hit = CACHE.get(key);
    if (hit != null) {
      view.bitmap(hit);
      return;
    }
    final Context c = view.getContext().getApplicationContext();
    final WeakReference<PlayerViews.Art> target = new WeakReference<PlayerViews.Art>(view);
    POOL.execute(
        new Runnable() {
          public void run() {
            Bitmap image = null;
            HttpURLConnection conn = null;
            try {
              URL u = new URL(url);
              if (!"https".equals(u.getProtocol()) && !"http".equals(u.getProtocol())) return;
              conn = (HttpURLConnection) u.openConnection();
              conn.setInstanceFollowRedirects(false);
              conn.setConnectTimeout(8000);
              conn.setReadTimeout(10000);
              if (conn instanceof HttpsURLConnection)
                ((HttpsURLConnection) conn).setSSLSocketFactory(Api.tls(c));
              if (conn.getResponseCode() != 200) return;
              if (conn.getContentLength() > 2 * 1024 * 1024) return;
              ByteArrayOutputStream out = new ByteArrayOutputStream();
              InputStream in = conn.getInputStream();
              try {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                  if (out.size() + n > 2 * 1024 * 1024) throw new IOException("Image too large");
                  out.write(buf, 0, n);
                }
              } finally {
                in.close();
              }
              byte[] bytes = out.toByteArray();
              BitmapFactory.Options opts = new BitmapFactory.Options();
              opts.inJustDecodeBounds = true;
              BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
              if (opts.outWidth <= 0
                  || opts.outHeight <= 0
                  || opts.outWidth > 10000
                  || opts.outHeight > 10000) return;
              opts.inSampleSize = 1;
              while (Math.max(opts.outWidth, opts.outHeight) / opts.inSampleSize > size * 2)
                opts.inSampleSize *= 2;
              opts.inJustDecodeBounds = false;
              opts.inPreferredConfig = Bitmap.Config.RGB_565;
              image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
              if (image != null) CACHE.put(key, image);
            } catch (Exception ignored) {
            } finally {
              if (conn != null) conn.disconnect();
            }
            final Bitmap result = image;
            MAIN.post(
                new Runnable() {
                  public void run() {
                    PlayerViews.Art v = target.get();
                    if (v != null && key.equals(v.getTag())) v.bitmap(result);
                  }
                });
          }
        });
  }
}
