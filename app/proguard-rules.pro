# Lifeline R8 rules. Libraries that ship their own rules (OkHttp, kotlinx.serialization, Compose, CameraX,
# Anthropic SDK) need nothing here.

# sshj + BouncyCastle + EdDSA: crypto algorithms and JCE providers are looked up by name at runtime.
-keep class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class net.i2p.crypto.eddsa.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn net.i2p.crypto.eddsa.**
-dontwarn javax.naming.**
-dontwarn sun.security.**
# sshj's Kerberos (GSS-API) login: desktop-only, never used by Lifeline.
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.login.**

# slf4j: we ship the no-op binding.
-dontwarn org.slf4j.**

# Anthropic SDK pulls in desktop-only optional pieces (Apache HttpClient, schema generation) we never call.
-dontwarn org.apache.hc.**
-dontwarn com.github.victools.**
-dontwarn com.fasterxml.jackson.**
-dontwarn java.beans.**
-dontwarn javax.annotation.**

# Keep line numbers so crash reports stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
