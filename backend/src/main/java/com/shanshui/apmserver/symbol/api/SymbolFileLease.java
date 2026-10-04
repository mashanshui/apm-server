package com.shanshui.apmserver.symbol.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/** 固定一个符号表版本的短生命周期读取租约。 */
public interface SymbolFileLease extends AutoCloseable {

    /** 返回符号表记录标识。 */
    UUID symbolId();

    /** 返回符号表版本。 */
    int revision();

    /** 返回当前固定文件版本的登记摘要，不能另查最新 mapping 代替。 */
    String sha256();

    /** 返回受控目录内的不可变文件路径。 */
    Path path();

    /** 释放读取租约。 */
    @Override
    void close() throws IOException;
}
