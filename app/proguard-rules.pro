# Add project specific ProGuard rules here.

# Sherpa-ONNX JNI models & config classes
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Preserve native JNI bridge methods (libmyasr_llm.so)
-keepclasseswithmembernames class * {
    native <methods>;
}
