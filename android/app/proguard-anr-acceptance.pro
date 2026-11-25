# 仅供显式 ANR 联调构建保留受控方法边界，仍允许 R8 混淆类名和方法名。
-keep,allowobfuscation class com.shanshui.performance.AnrSymbolicationProbe { *; }
