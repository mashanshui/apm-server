package com.shanshui.apmserver.symbol.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 符号表上传、文件和 Retrace 资源限制。 */
@ConfigurationProperties(prefix = "apm.symbol")
public class SymbolProperties {

    /** 默认单份 mapping 上限。 */
    public static final long DEFAULT_MAX_FILE_BYTES = 32L * 1024 * 1024;
    /** 默认单次还原输出上限。 */
    public static final long DEFAULT_MAX_OUTPUT_BYTES = 1024L * 1024;
    /** 默认共享解析并发数。 */
    public static final int DEFAULT_MAX_CONCURRENT_OPERATIONS = 2;

    /** 单份 mapping 的最大字节数。 */
    private long maxFileBytes = DEFAULT_MAX_FILE_BYTES;
    /** 单次还原输出的最大字节数。 */
    private long maxOutputBytes = DEFAULT_MAX_OUTPUT_BYTES;
    /** 上传校验和 Crash Retrace 的共享并发数。 */
    private int maxConcurrentOperations = DEFAULT_MAX_CONCURRENT_OPERATIONS;
    /** 符号文件受控根目录。 */
    private String directory = "build/symbols";
    /** Linux 资源限制启动器的绝对路径；未配置时不执行 Native 地址还原。 */
    private String nativeLauncherPath = "";

    /** 返回单份 mapping 的最大字节数。 */
    public long getMaxFileBytes() {
        return maxFileBytes;
    }

    /** 设置单份 mapping 的最大字节数。 */
    public void setMaxFileBytes(long maxFileBytes) {
        this.maxFileBytes = maxFileBytes;
    }

    /** 返回单次还原输出的最大字节数。 */
    public long getMaxOutputBytes() {
        return maxOutputBytes;
    }

    /** 设置单次还原输出的最大字节数。 */
    public void setMaxOutputBytes(long maxOutputBytes) {
        this.maxOutputBytes = maxOutputBytes;
    }

    /** 返回共享解析并发数。 */
    public int getMaxConcurrentOperations() {
        return maxConcurrentOperations;
    }

    /** 设置共享解析并发数。 */
    public void setMaxConcurrentOperations(int maxConcurrentOperations) {
        this.maxConcurrentOperations = maxConcurrentOperations;
    }

    /** 返回符号文件受控根目录。 */
    public String getDirectory() {
        return directory;
    }

    /** 设置符号文件受控根目录。 */
    public void setDirectory(String directory) {
        this.directory = directory;
    }

    /** 返回 Native 工具资源限制启动器路径。 */
    public String getNativeLauncherPath() {
        return nativeLauncherPath;
    }

    /** 设置 Native 工具资源限制启动器路径。 */
    public void setNativeLauncherPath(String nativeLauncherPath) {
        this.nativeLauncherPath = nativeLauncherPath;
    }
}
