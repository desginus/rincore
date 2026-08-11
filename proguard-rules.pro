
# v3.6.17 功耗优化: release 裁剪 debug/verbose 日志 (每 chunk SSE 日志格式化是热点)
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
