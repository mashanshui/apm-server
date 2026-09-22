package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.crash.api.CrashPayload;
import com.shanshui.apmserver.crash.api.ThrowableNode;
import com.shanshui.apmserver.telemetry.api.StackFrame;

import java.util.ArrayList;
import java.util.List;

/** 将结构化 Crash 异常链转换成 R8 Retrace 接受的完整文本行。 */
final class CrashStackTraceFormatter {

    /** 禁止实例化纯工具类。 */
    private CrashStackTraceFormatter() {
    }

    /** 保持异常链顺序、原因关系和已有行号地生成堆栈文本。 */
    static List<String> lines(CrashPayload payload) {
        List<String> result = new ArrayList<>();
        if (payload == null || payload.throwableChain() == null) {
            return result;
        }
        for (int index = 0; index < payload.throwableChain().size(); index++) {
            ThrowableNode node = payload.throwableChain().get(index);
            if (index == 0) {
                result.add(header(node));
            } else {
                result.add("Caused by: " + header(node));
            }
            if (node.frames() != null) {
                node.frames().forEach(frame -> result.add(frame(frame)));
            }
        }
        return result;
    }

    /** 生成异常类型和消息行。 */
    private static String header(ThrowableNode node) {
        String type = node.type() == null ? "java.lang.Throwable" : node.type();
        return node.message() == null || node.message().isBlank() ? type : type + ": " + node.message();
    }

    /** 生成 R8 可识别的 Java stack frame 行。 */
    private static String frame(StackFrame frame) {
        String className = frame.className() == null ? "unknown" : frame.className();
        String methodName = frame.methodName() == null ? "unknown" : frame.methodName();
        String fileName = frame.fileName() == null ? "Unknown Source" : frame.fileName();
        String location = frame.lineNumber() == null ? fileName : fileName + ":" + frame.lineNumber();
        return "\tat " + className + "." + methodName + "(" + location + ")";
    }
}
