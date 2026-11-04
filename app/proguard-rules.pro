# kotlinx.serialization: keep generated serializers (ops are persisted and sent over the wire,
# so a renamed/stripped serializer would corrupt data, not just crash).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.braid.**$$serializer { *; }

# BouncyCastle: only the lightweight API is used; silence warnings about optional JCE/LDAP bits.
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
