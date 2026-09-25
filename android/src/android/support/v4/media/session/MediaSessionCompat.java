package android.support.v4.media.session;

import android.os.*;

/** Wire-only Parcelable name required by the original API 19 media service; not the support SDK. */
public final class MediaSessionCompat {
  private MediaSessionCompat() {}

  public static final class Token implements Parcelable {
    public final IBinder binder;

    public Token(IBinder binder) {
      this.binder = binder;
    }

    public int describeContents() {
      return 0;
    }

    public void writeToParcel(Parcel parcel, int flags) {
      parcel.writeStrongBinder(binder);
    }

    public static final Parcelable.Creator<Token> CREATOR =
        new Parcelable.Creator<Token>() {
          public Token createFromParcel(Parcel parcel) {
            return new Token(parcel.readStrongBinder());
          }

          public Token[] newArray(int count) {
            return new Token[count];
          }
        };
  }
}
