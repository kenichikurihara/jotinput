# Add project specific ProGuard rules here.

# Sherpa-ONNX JNI models & config classes (AAR has empty consumer proguard.txt)
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Preserve native JNI bridge classes & methods (libmyasr_llm.so)
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.example.myasr.context.reranking.infrastructure.LlmReranker {
    native <methods>;
}
-keep class com.example.myasr.context.english.infrastructure.FutoSwipeEngine {
    native <methods>;
}
-keep class com.example.myasr.context.english.infrastructure.EnglishSpellEngine {
    native <methods>;
}

# Retain source file & line numbers so R8 mapping.txt can de-obfuscate release crash traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
