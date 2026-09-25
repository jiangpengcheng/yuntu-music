-keep class io.github.yuntumusic.direct.** { *; }
-dontobfuscate
-dontoptimize
# API 19 media-browser Bundle carries this exact Parcelable class name.
-keep class android.support.v4.media.session.MediaSessionCompat$Token { *; }
