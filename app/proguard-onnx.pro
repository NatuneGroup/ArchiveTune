## ONNX Runtime, applied only to -Pautomix=true builds (the only ones that ship the runtime).
# libonnxruntime4j_jni resolves ai.onnxruntime classes, fields and constructors by name
# (FindClass/GetMethodID). Once R8 renamed TensorInfo and friends, the first OrtSession.run
# aborted the whole process with
#   JNI DETECTED ERROR IN APPLICATION: java_class == null in call to GetMethodID
# (issue #182). The AAR ships no consumer rules, so keep the package intact here.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
