package com.shanshui.apmserver.symbol.internal.elf;

import java.io.IOException;

/** ELF 内容错误的稳定安全类别，不携带上传内容或文件路径。 */
public final class ElfValidationException extends IOException {
    /** 对外稳定错误码。 */
    private final String code;
    /** 指定内容错误的类别。 */
    public ElfValidationException(String code) { super("ELF 内容不满足符号登记要求"); this.code = code; }
    /** 返回可展示的类别。 */
    public String code() { return code; }
}
