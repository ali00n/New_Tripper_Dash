# Código do app inteiro preservado (protocolo, serviço, receivers); R8 só enxuga as bibliotecas.
-keep class br.com.evandro.tripper.** { *; }
-dontwarn **
# 22/11: um usuário (Realme 12+) relatou que o app abria e fechava. O MapLibre chama classes e campos
# pelo código nativo (JNI) e o R8 não enxerga: mantém as bibliotecas de mapa e de rede inteiras.
-keep class org.maplibre.** { *; }
-keep interface org.maplibre.** { *; }
-keep class com.google.gson.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
