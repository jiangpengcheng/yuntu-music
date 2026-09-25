package io.github.yuntumusic.direct;

import android.util.Base64;
import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.zip.GZIPInputStream;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.*;
import org.bouncycastle.math.ec.rfc7748.X25519;
import org.json.*;

/** Protocol port from API Enhanced 4.40.1. No provider replacement or JS runtime. */
final class NativeCrypto {
  static final SecureRandom RANDOM = new SecureRandom();
  static final byte[] EAPI = bytes("e82ckenh8dichen8");
  static final byte[] XEAPI =
      unhex("ab1d5a430f6bb04a3f01e81ddd72bd916d5ce591248ac128714806d7f8fb1b84");
  static final String SIGN =
      "mUHCwVNWJbunMqAHf5MImuirT6plvs6VSFW62MGHstFQxhBGdEoIhLItH3djc4+FB/OKty3+lL2rGeoFBpVe5g==";

  static byte[] bytes(String s) {
    try {
      return s.getBytes("UTF-8");
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  static String text(byte[] b) {
    try {
      return new String(b, "UTF-8");
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  static String b64(byte[] b) {
    return Base64.encodeToString(b, Base64.NO_WRAP);
  }

  static byte[] un64(String s) {
    return Base64.decode(s, Base64.DEFAULT);
  }

  static byte[] random(int n) {
    byte[] b = new byte[n];
    RANDOM.nextBytes(b);
    return b;
  }

  static String hex(byte[] b) {
    StringBuilder s = new StringBuilder();
    for (byte v : b) s.append(String.format(Locale.US, "%02x", v & 255));
    return s.toString();
  }

  static byte[] unhex(String s) {
    byte[] b = new byte[s.length() / 2];
    for (int i = 0; i < b.length; i++)
      b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
    return b;
  }

  static byte[] join(byte[]... list) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] b : list) out.write(b);
    return out.toByteArray();
  }

  static byte[] aes(byte[] data, byte[] key, byte[] iv, boolean encrypt) throws Exception {
    Cipher c = Cipher.getInstance(iv == null ? "AES/ECB/PKCS5Padding" : "AES/CBC/PKCS5Padding");
    if (iv == null)
      c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
    else
      c.init(
          encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new IvParameterSpec(iv));
    return c.doFinal(data);
  }

  static byte[] hmac(byte[] key, byte[] data) throws Exception {
    Mac m = Mac.getInstance("HmacSHA256");
    m.init(new SecretKeySpec(key, "HmacSHA256"));
    return m.doFinal(data);
  }

  static String sign(String time, String nonce) throws Exception {
    return b64(hmac(bytes(SIGN), bytes(time + nonce)));
  }

  static String form(JSONObject data) throws Exception {
    StringBuilder out = new StringBuilder();
    Iterator<String> it = data.keys();
    while (it.hasNext()) {
      String k = it.next();
      if (out.length() > 0) out.append('&');
      out.append(Api.encode(k)).append('=').append(Api.encode(String.valueOf(data.get(k))));
    }
    return out.toString();
  }

  static JSONObject weapi(JSONObject data) throws Exception {
    String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    StringBuilder s = new StringBuilder();
    for (int i = 0; i < 16; i++) s.append(alphabet.charAt(RANDOM.nextInt(62)));
    String secret = s.toString();
    byte[] iv = bytes("0102030405060708");
    String params =
        b64(
            aes(
                bytes(b64(aes(bytes(data.toString()), bytes("0CoJUm6Qyw8W8jud"), iv, true))),
                bytes(secret),
                iv,
                true));
    String pem =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFbt7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZMldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB";
    RSAPublicKey key =
        (RSAPublicKey)
            KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(un64(pem)));
    String rsa =
        new BigInteger(1, bytes(s.reverse().toString()))
            .modPow(key.getPublicExponent(), key.getModulus())
            .toString(16);
    while (rsa.length() < 256) rsa = "0" + rsa;
    return new JSONObject().put("params", params).put("encSecKey", rsa);
  }

  static JSONObject eapi(String uri, JSONObject data) throws Exception {
    String text = data.toString();
    String md5 =
        hex(
            MessageDigest.getInstance("MD5")
                .digest(bytes("nobody" + uri + "use" + text + "md5forencrypt")));
    return new JSONObject()
        .put(
            "params",
            hex(aes(bytes(uri + "-36cd479b6b5-" + text + "-36cd479b6b5-" + md5), EAPI, null, true))
                .toUpperCase(Locale.US));
  }

  static byte[] gcm(byte[] key, byte[] iv, byte[] plain) throws Exception {
    GCMBlockCipher c = new GCMBlockCipher(new AESEngine());
    c.init(true, new AEADParameters(new KeyParameter(key), 128, iv));
    byte[] out = new byte[c.getOutputSize(plain.length)];
    int n = c.processBytes(plain, 0, plain.length, out, 0);
    n += c.doFinal(out, n);
    return Arrays.copyOf(out, n);
  }

  static JSONObject xeapi(String uri, JSONObject data, JSONObject state) throws Exception {
    JSONObject body = new JSONObject(data.toString());
    body.remove("e_r");
    byte[] plain =
        bytes(
            new JSONObject()
                .put("body", b64(bytes(form(body))))
                .put("queryString", "e_r=true")
                .toString());
    byte[] dynamic = random(16), mask = random(16), cipher = aes(plain, XEAPI, null, true);
    for (int i = 0; i < cipher.length; i++) cipher[i] ^= mask[i & 15];
    byte[] mid = bytes(b64(cipher));
    int rot = (mask[0] & 15) % mid.length;
    byte[] b =
        aes(
            join(mask, Arrays.copyOfRange(mid, rot, mid.length), Arrays.copyOfRange(mid, 0, rot)),
            dynamic,
            null,
            true);
    byte[] privateKey = new byte[32], pub = new byte[32], shared = new byte[32];
    X25519.generatePrivateKey(RANDOM, privateKey);
    X25519.generatePublicKey(privateKey, 0, pub, 0);
    byte[] peer = un64(state.getString("publicKey"));
    if (peer.length != 32 || !X25519.calculateAgreement(privateKey, 0, peer, 0, shared, 0))
      throw new GeneralSecurityException("无效的接口加密公钥");
    byte[] prk = hmac(new byte[32], shared),
        key = Arrays.copyOf(hmac(prk, join(pub, new byte[] {1})), 16),
        iv = random(12);
    byte[] s =
        join(pub, iv, gcm(key, iv, bytes(b64(dynamic) + "|android|" + state.getString("sk"))));
    Arrays.fill(privateKey, (byte) 0);
    Arrays.fill(shared, (byte) 0);
    return new JSONObject()
        .put("B", b64(b))
        .put("S", b64(s))
        .put("R", b64(aes(bytes(state.getString("version") + "|"), XEAPI, null, true)));
  }

  static byte[] bounded(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] b = new byte[4096];
    int n;
    while ((n = in.read(b)) != -1) {
      if (out.size() + n > 2 * 1024 * 1024) throw new IOException("接口响应过大");
      out.write(b, 0, n);
    }
    return out.toByteArray();
  }

  static JSONObject response(byte[] raw, boolean encrypted) throws Exception {
    if (encrypted) { // Error responses may still be plain JSON.
      if (raw.length > 0 && raw[0] == '{') return new JSONObject(text(raw));
      raw = aes(raw, EAPI, null, false);
    }
    if (raw.length > 1 && raw[0] == 0x1f && (raw[1] & 255) == 0x8b) {
      InputStream in = new GZIPInputStream(new ByteArrayInputStream(raw));
      try {
        raw = bounded(in);
      } finally {
        in.close();
      }
    }
    return new JSONObject(text(raw));
  }
}
