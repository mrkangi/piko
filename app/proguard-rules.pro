# Keep this file narrow. Android manifest components are kept by the Android
# Gradle Plugin, and the app calls its Kotlin Serialization serializers
# directly, so package-wide keep rules would only reduce R8 optimization.

# smbj 的 SPNEGO/JNDI 可选路径在 Android 上没有 javax.el 与 org.ietf.jgss；
# NTLM 鉴权不走这些类。
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**

# Paho 通过 META-INF/services 查找 NetworkModuleFactory，R8 不会自行保留这些实现。
-keep class org.eclipse.paho.client.mqttv3.internal.TCPNetworkModuleFactory { *; }
-keep class org.eclipse.paho.client.mqttv3.internal.SSLNetworkModuleFactory { *; }
-keep class org.eclipse.paho.client.mqttv3.internal.websocket.WebSocketNetworkModuleFactory { *; }
-keep class org.eclipse.paho.client.mqttv3.internal.websocket.WebSocketSecureNetworkModuleFactory { *; }
-keep class org.eclipse.paho.client.mqttv3.logging.** { *; }

# 实时通道的常量与协程入口不能被 R8 折叠掉；它只负责远程下载的加速，保留成本很小。
-keep class dev.piko.shared.download.RemoteRealtimeTransport { *; }
-keep class dev.piko.shared.download.MqttRemoteRealtimeTransport { *; }
-keep class dev.piko.shared.download.MqttRemoteRealtimeTransport$* { *; }
