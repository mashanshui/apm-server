# Crash JSON DTO、队列记录和异常映射字段属于协议与本地格式。
-keepattributes Signature,*Annotation*,SourceFile,LineNumberTable
-keepclassmembers class com.shanshui.performance.network.** {
    <fields>;
}
-keepclassmembers class com.shanshui.performance.crash.** {
    <fields>;
}
# JNI 按固定类名和方法名查找信号桥与普通工作者回调。
-keep class com.shanshui.performance.crash.NativeCrashBridge {
    native <methods>;
    boolean onNativeCaptureCompleted(long, int, int, long);
}
