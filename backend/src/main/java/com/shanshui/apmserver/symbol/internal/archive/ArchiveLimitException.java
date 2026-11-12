package com.shanshui.apmserver.symbol.internal.archive;

/** 实际解压字节超过预算，区别于普通格式错误。 */
public final class ArchiveLimitException extends java.io.IOException {
    /** 返回不含内容的预算错误。 */
    public ArchiveLimitException() { super("SO 解压字节超过预算"); }
}
