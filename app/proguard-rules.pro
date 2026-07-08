# LiteRT / QNN delegate は JNI 経由で参照されるため難読化から除外
-keep class org.tensorflow.lite.** { *; }
-keep class com.qualcomm.qti.** { *; }
