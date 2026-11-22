# 原生代码通过名称查找 Matrix 迁移后的采集器及静态回调。
-keep class com.shanshui.performance.anr.internal.SignalAnrTracer {
    native <methods>;
    private static void onANRDumped();
    private static void onANRDumpTrace();
    private static void onPrintTrace();
    private static void onNativeBacktraceDumped();
}

# 报告 JSON 使用固定字段名，确保压缩后及应用升级后仍可读取。
-keepclassmembers,allowobfuscation class com.shanshui.performance.anr.** {
    @com.google.gson.annotations.SerializedName <fields>;
}
