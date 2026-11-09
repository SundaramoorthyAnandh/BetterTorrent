# libtorrent4j: SWIG/JNI bindings are looked up by name from native code.
-keep class org.libtorrent4j.** { *; }
-keepclassmembers class org.libtorrent4j.** { native <methods>; }
-dontwarn org.libtorrent4j.**

# NanoHTTPD
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**
