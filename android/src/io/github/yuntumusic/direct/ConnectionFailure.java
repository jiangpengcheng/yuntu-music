package io.github.yuntumusic.direct;

import java.io.EOFException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.util.Locale;
import javax.net.ssl.*;

/** Classify errors without exposing exception messages containing addresses or credentials. */
final class ConnectionFailure {
  private ConnectionFailure() {}

  static String reason(Throwable error) {
    boolean tls = false, transport = false, protocol = false;
    int depth = 0;
    for (Throwable cause = error; cause != null && depth++ < 8; cause = cause.getCause()) {
      String message = cause.getMessage() == null ? "" : cause.getMessage().toLowerCase(Locale.US);
      if (cause instanceof SSLPeerUnverifiedException) return "tls_identity";
      if (cause instanceof CertificateException || cause instanceof CertPathValidatorException)
        return "tls_certificate";
      if (message.contains("certificate")
          || message.contains("trust anchor")
          || message.contains("certpath")
          || message.contains("hostname")) return "tls_certificate";
      if (cause instanceof SSLException) tls = true;
      if (cause instanceof SSLProtocolException
          || cause instanceof SSLKeyException
          || message.contains("protocol_version")
          || message.contains("handshake_failure")
          || message.contains("no cipher")
          || message.contains("unsupported protocol")) protocol = true;
      if (cause instanceof EOFException
          || cause instanceof SocketException
          || cause instanceof SocketTimeoutException) transport = true;
      if (cause.getCause() == cause) break;
    }
    if (protocol) return "tls_protocol";
    if (tls) return "tls_connection";
    return transport ? "connection_interrupted" : "request_error";
  }

  static boolean retryable(String method, int status, Throwable error) {
    if (!"GET".equals(method) || (status >= 0 && (status < 200 || status >= 300))) return false;
    String reason = reason(error);
    return reason.equals("tls_connection") || reason.equals("connection_interrupted");
  }

  static String message(Throwable error) {
    String reason = reason(error);
    if (reason.equals("tls_identity")) return "服务身份校验失败，请检查服务地址与证书域名";
    if (reason.equals("tls_certificate")) return "服务证书校验失败，请检查车机日期和证书信任链";
    if (reason.equals("tls_protocol")) return "TLS 协议协商失败，请检查服务是否兼容 Android 4.4";
    if (reason.equals("tls_connection")) return "安全连接暂时失败，请检查网络后重试";
    if (reason.equals("connection_interrupted")) return "网络连接中断或超时，请稍后重试";
    return error.getMessage() == null ? "连接失败，请检查网络和服务地址" : error.getMessage();
  }

  static String route(String path) {
    String route = path.split("\\?", 2)[0].replaceAll("/\\d+(?=/|$)", "/:id");
    if (route.matches("/v1/(session|logout|login/qr|login/qr/check|playlists|search|fm)"))
      return route;
    if (route.matches("/v1/(songs|playlists|artists|albums)/:id/(url|presentation|like|tracks)"))
      return route;
    return "other";
  }

  static String types(Throwable error) {
    StringBuilder out = new StringBuilder();
    int depth = 0;
    for (Throwable c = error; c != null && depth++ < 6; c = c.getCause()) {
      if (out.length() > 0) out.append('/');
      out.append(c.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_]", ""));
      if (c.getCause() == c) break;
    }
    return out.toString();
  }
}
