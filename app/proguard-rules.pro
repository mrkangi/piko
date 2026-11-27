# Keep this file narrow. Android manifest components are kept by the Android
# Gradle Plugin, and the app calls its Kotlin Serialization serializers
# directly, so package-wide keep rules would only reduce R8 optimization.

# smbj 的 SPNEGO/JNDI 可选路径在 Android 上没有 javax.el 与 org.ietf.jgss；
# NTLM 鉴权不走这些类。
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
