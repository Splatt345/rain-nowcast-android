# onnxruntime binds Java classes from JNI by name.
-keep class ai.onnxruntime.** { *; }
# jHDF picks HDF5 message and filter classes reflectively.
-keep class io.jhdf.** { *; }
-dontwarn io.jhdf.**
-dontwarn org.slf4j.**
