package io.github.yuntumusic.direct;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Extend the normal backend trust store with one public CA missing on KitKat. */
public final class BackendTrust {
  private BackendTrust() {}

  public static X509TrustManager system() throws Exception {
    TrustManagerFactory factory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    factory.init((KeyStore) null);
    return x509(factory.getTrustManagers());
  }

  public static X509TrustManager withExtraRoot(final X509TrustManager system, InputStream pem)
      throws Exception {
    X509Certificate root =
        (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(pem);
    KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
    store.load(null, null);
    store.setCertificateEntry("isrg-root-x1", root);
    TrustManagerFactory factory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    factory.init(store);
    final X509TrustManager additional = x509(factory.getTrustManagers());
    return new X509TrustManager() {
      public void checkClientTrusted(X509Certificate[] chain, String type)
          throws CertificateException {
        system.checkClientTrusted(chain, type);
      }

      public void checkServerTrusted(X509Certificate[] chain, String type)
          throws CertificateException {
        try {
          system.checkServerTrusted(chain, type);
        } catch (CertificateException oldStore) {
          additional.checkServerTrusted(chain, type);
        }
      }

      public X509Certificate[] getAcceptedIssuers() {
        X509Certificate[] a = system.getAcceptedIssuers(), b = additional.getAcceptedIssuers();
        X509Certificate[] out = new X509Certificate[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
      }
    };
  }

  private static X509TrustManager x509(TrustManager[] managers) throws Exception {
    for (TrustManager m : managers) if (m instanceof X509TrustManager) return (X509TrustManager) m;
    throw new Exception("X509 trust manager unavailable");
  }
}
