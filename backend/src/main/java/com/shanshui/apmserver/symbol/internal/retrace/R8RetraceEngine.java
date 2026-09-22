package com.shanshui.apmserver.symbol.internal.retrace;

import com.shanshui.apmserver.symbol.api.SymbolicationResult;
import com.shanshui.apmserver.symbol.api.SymbolValidationException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 通过官方 R8 Retrace Java API 执行 mapping 校验和堆栈还原。 */
@Component
public class R8RetraceEngine {

    /** 校验 mapping 文件的完整定义。 */
    public void validate(Path mapping) {
        try {
            if (Files.size(mapping) == 0 || !containsMappingDefinition(mapping)) {
                throw new SymbolValidationException("INVALID_MAPPING", "mapping 文件不是有效的 R8/ProGuard 格式", 422);
            }
            Object supplier = buildSupplier(mapping, true);
            forceMappingRead(supplier);
        } catch (SymbolValidationException ex) {
            throw ex;
        } catch (IOException | ReflectiveOperationException | RuntimeException ex) {
            throw new SymbolValidationException("UNSUPPORTED_MAPPING_VERSION",
                    "mapping 文件无法由当前 R8 Retrace 解析", 422);
        }
    }

    /** 还原完整异常链文本并保留 R8 返回的多候选输出。 */
    public SymbolicationResult retrace(Path mapping, List<String> stackLines, long maxOutputBytes) {
        try {
            Object supplier = buildSupplier(mapping, false);
            OutputCollector output = new OutputCollector(maxOutputBytes);
            Object commandBuilder = createCommandBuilder();
            invokeFluent(commandBuilder, "setMappingSupplier", supplier);
            invokeStackTrace(commandBuilder, stackLines);
            invokeConsumer(commandBuilder, output);
            Object command = invokeNoArg(commandBuilder, "build");
            Class<?> retrace = Class.forName("com.android.tools.r8.retrace.Retrace");
            // R8 同时暴露 run(RetraceCommand) 和 run(String[])，不能只按参数数量选择重载。
            Method run = findCompatibleMethod(retrace, "run", command);
            run.invoke(null, command);
            String text = output.text();
            if (output.exceeded() || text.getBytes(StandardCharsets.UTF_8).length > maxOutputBytes) {
                return SymbolicationResult.failed("output_limit");
            }
            return new SymbolicationResult(text, SymbolicationResult.Status.SYMBOLICATED, null);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return SymbolicationResult.failed("retrace_failed");
        }
    }

    /** 构建官方 mapping supplier，并决定是否加载全部定义。 */
    private Object buildSupplier(Path mapping, boolean loadAllDefinitions)
            throws ReflectiveOperationException {
        Class<?> producerType = Class.forName("com.android.tools.r8.retrace.ProguardMapProducer");
        Method producerFactory = findPathFactory(producerType, "fromPath");
        Object producer = producerFactory.invoke(null,
                producerFactory.getParameterTypes()[0] == Path.class ? mapping : mapping.toString());

        Class<?> supplierType = Class.forName("com.android.tools.r8.retrace.ProguardMappingSupplier");
        Object builder = invokeStaticNoArg(supplierType, "builder");
        invokeFluent(builder, "setProguardMapProducer", producer);
        invokeOptionalFluent(builder, "setAllowExperimental", false);
        invokeOptionalFluent(builder, "setLoadAllDefinitions", loadAllDefinitions);
        return invokeNoArg(builder, "build");
    }

    /** 创建 R8 Retrace 命令构建器。 */
    private Object createCommandBuilder() throws ReflectiveOperationException {
        Class<?> commandType = Class.forName("com.android.tools.r8.retrace.RetraceCommand");
        for (Method method : commandType.getMethods()) {
            if (method.getName().equals("builder") && method.getParameterCount() == 0) {
                return method.invoke(null);
            }
        }
        for (Method method : commandType.getMethods()) {
            if (!method.getName().equals("builder") || method.getParameterCount() != 1) {
                continue;
            }
            Object diagnostics = diagnosticsArgument(method.getParameterTypes()[0]);
            return method.invoke(null, diagnostics);
        }
        throw new NoSuchMethodException("RetraceCommand.builder");
    }

    /** 优先使用官方 List 重载，旧版本再通过 StackTraceSupplier 提供一次性输入。 */
    private void invokeStackTrace(Object builder, List<String> stackLines)
            throws ReflectiveOperationException {
        for (Method method : builder.getClass().getMethods()) {
            if (!method.getName().equals("setStackTrace") || method.getParameterCount() != 1) {
                continue;
            }
            Class<?> parameter = method.getParameterTypes()[0];
            if (parameter.isAssignableFrom(stackLines.getClass())) {
                method.invoke(builder, stackLines);
                return;
            }
        }
        Class<?> supplierType = Class.forName("com.android.tools.r8.retrace.StackTraceSupplier");
        AtomicBoolean supplied = new AtomicBoolean();
        Object supplier = Proxy.newProxyInstance(supplierType.getClassLoader(),
                new Class<?>[]{supplierType}, (proxy, method, args) -> {
                    if ("get".equals(method.getName()) && supplied.compareAndSet(false, true)) {
                        return stackLines;
                    }
                    return null;
                });
        invokeFluent(builder, "setStackTrace", supplier);
    }

    /** 将 R8 还原结果消费者安装到命令构建器。 */
    private void invokeConsumer(Object builder, OutputCollector output) throws ReflectiveOperationException {
        for (Method method : builder.getClass().getMethods()) {
            if (!method.getName().equals("setRetracedStackTraceConsumer")
                    || method.getParameterCount() != 1) {
                continue;
            }
            Class<?> consumerType = method.getParameterTypes()[0];
            if (consumerType.isAssignableFrom(Consumer.class)) {
                method.invoke(builder, (Consumer<Object>) value -> appendRetracedValue(output, value));
                return;
            }
            if (consumerType.isInterface()) {
                InvocationHandler handler = (proxy, invoked, args) -> {
                    if (args != null && args.length > 0) {
                        appendRetracedValue(output, args[0]);
                    }
                    return null;
                };
                method.invoke(builder, Proxy.newProxyInstance(consumerType.getClassLoader(),
                        new Class<?>[]{consumerType}, handler));
                return;
            }
        }
        throw new NoSuchMethodException("RetraceCommand.setRetracedStackTraceConsumer");
    }

    /** 将 R8 结果对象转为文本，不记录 mapping 或异常内容到日志。 */
    private void appendRetracedValue(OutputCollector output, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof CharSequence text) {
            output.add(text.toString());
            return;
        }
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> appendRetracedValue(output, item));
            return;
        }
        for (String methodName : List.of("getRetracedStackTrace", "getLines", "getStackTrace")) {
            try {
                Method method = value.getClass().getMethod(methodName);
                Object nested = method.invoke(value);
                if (nested != value) {
                    appendRetracedValue(output, nested);
                    return;
                }
            } catch (ReflectiveOperationException ignored) {
                // 不同 R8 版本的结果包装类型不同，继续尝试下一种公开访问器。
            }
        }
        output.add(value.toString());
    }

    /** 在 R8 回调阶段限制累计输出，避免超限结果先完整驻留堆内存。 */
    private static final class OutputCollector {

        /** 还原文本行。 */
        private final List<String> lines = new ArrayList<>();
        /** 最大 UTF-8 字节数。 */
        private final long maxBytes;
        /** 当前累计 UTF-8 字节数。 */
        private long bytes;
        /** 是否已经达到上限。 */
        private boolean exceeded;

        /** 创建带输出上限的收集器。 */
        private OutputCollector(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        /** 添加一行，超限后丢弃后续内容。 */
        private void add(String line) {
            if (exceeded) {
                return;
            }
            long next = bytes + line.getBytes(StandardCharsets.UTF_8).length + 1L;
            if (next > maxBytes) {
                exceeded = true;
                return;
            }
            lines.add(line);
            bytes = next;
        }

        /** 返回已收集文本。 */
        private String text() {
            return String.join("\n", lines);
        }

        /** 返回是否超过上限。 */
        private boolean exceeded() {
            return exceeded;
        }
    }

    /** 主动触发 R8 mapping 读取，使非法定义在上传阶段被拒绝。 */
    private void forceMappingRead(Object supplier) throws ReflectiveOperationException {
        for (Method method : supplier.getClass().getMethods()) {
            if (!method.getName().equals("createRetracer") || method.getParameterCount() != 1) {
                continue;
            }
            Object diagnostics = diagnosticsArgument(method.getParameterTypes()[0]);
            method.invoke(supplier, diagnostics);
            return;
        }
        throw new NoSuchMethodException("MappingSupplier.createRetracer");
    }

    /** 检查文本中存在至少一个类定义映射，提前拒绝空文件。 */
    private boolean containsMappingDefinition(Path mapping) throws IOException {
        try (var lines = Files.lines(mapping, StandardCharsets.UTF_8)) {
            return lines.anyMatch(line -> line.contains("->") && line.trim().endsWith(":"));
        }
    }

    /** 创建尽可能兼容不同 R8 版本的诊断处理器参数。 */
    private Object diagnosticsArgument(Class<?> type) {
        if (!type.isInterface()) {
            return null;
        }
        InvocationHandler handler = (proxy, method, args) -> null;
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    /** 查找 Path 或 String 工厂方法。 */
    private Method findPathFactory(Class<?> type, String name) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1
                    && (method.getParameterTypes()[0] == Path.class
                    || method.getParameterTypes()[0] == String.class)) {
                return method;
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    /** 调用无参静态方法。 */
    private Object invokeStaticNoArg(Class<?> type, String name) throws ReflectiveOperationException {
        return findMethod(type, name, 0).invoke(null);
    }

    /** 调用无参实例方法。 */
    private Object invokeNoArg(Object target, String name) throws ReflectiveOperationException {
        return findMethod(target.getClass(), name, 0).invoke(target);
    }

    /** 调用一个参数的 fluent 方法。 */
    private void invokeFluent(Object target, String name, Object value) throws ReflectiveOperationException {
        Method method = findCompatibleMethod(target.getClass(), name, value);
        method.invoke(target, value);
    }

    /** 调用可选 fluent 方法，不同发行版缺失时保持兼容。 */
    private void invokeOptionalFluent(Object target, String name, Object value) throws ReflectiveOperationException {
        try {
            invokeFluent(target, name, value);
        } catch (NoSuchMethodException ignored) {
            // 发行版没有该可选开关时使用默认行为。
        }
    }

    /** 查找名称和参数兼容的实例方法。 */
    private Method findCompatibleMethod(Class<?> type, String name, Object value) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1
                    && (value == null || boxed(method.getParameterTypes()[0]).isInstance(value))) {
                return method;
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    /** 查找指定名称和参数数量的方法。 */
    private Method findMethod(Class<?> type, String name, int parameterCount) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                return method;
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    /** 将基本类型转成包装类型用于反射匹配。 */
    private Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) return Boolean.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }
}
