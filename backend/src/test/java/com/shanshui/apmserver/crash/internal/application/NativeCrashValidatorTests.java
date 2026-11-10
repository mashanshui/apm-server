package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.crash.api.CrashDiagnostics;
import com.shanshui.apmserver.crash.api.NativeCrash;
import com.shanshui.apmserver.telemetry.api.ValidationIssue;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 Native 核心现场的位宽、引用和诊断线程边界。 */
class NativeCrashValidatorTests {
    /** 测试使用与接收器一致的 JSON 编码方式。 */
    private final NativeCrashValidator validator = new NativeCrashValidator(new ObjectMapper());

    /** 零故障地址与超过 JavaScript 安全整数的寄存器值必须无损接受。 */
    @Test
    void acceptsPreciseRegistersAndZeroAddress() {
        assertThat(validate(crash("arm64-v8a", "0xffffffffffffffff", "module", "0xb884"), null)).isEmpty();
    }

    /** 无法回溯时保留空帧和真实失败状态，不要求伪造调用栈。 */
    @Test
    void acceptsFailedBacktraceWithEmptyFrames() {
        NativeCrash original = crash("arm64-v8a", "0x0", "module", "0xb884");
        NativeCrash minimal = new NativeCrash(original.abi(), original.systemTid(), null, null, original.signal(),
                original.registers(), List.of(), new NativeCrash.Backtrace("unavailable", "io_error", false, 10L, List.of()), null);
        assertThat(validate(minimal, null)).isEmpty();
    }

    /** 超出 ABI 位宽、非规范地址和未知模块引用必须被拒绝。 */
    @Test
    void rejectsInvalidWidthsAndMissingModules() {
        assertThat(validate(crash("x86", "0x100000000", "module", "0x1"), null)).isNotEmpty();
        assertThat(validate(crash("arm64-v8a", "0x00", "module", "0x1"), null)).isNotEmpty();
        assertThat(validate(crash("arm64-v8a", "0x0", "missing", "0x1"), null)).isNotEmpty();
    }

    /** Native 辅助线程不得重复崩溃 TID，即使线程名不同也不能绕过。 */
    @Test
    void rejectsCrashedTidInNativeThreads() {
        NativeCrash original = crash("arm64-v8a", "0x0", "module", "0x1");
        var duplicate = new CrashDiagnostics.NativeThread(7, "same", false, original.backtrace());
        var nativeThreads = new CrashDiagnostics.NativeThreads("ok", null, false, 10L, 1, 0, List.of(duplicate));
        var diagnostics = new CrashDiagnostics(null, null, null, null, null, null, null, null, nativeThreads);
        assertThat(validate(original, diagnostics)).isNotEmpty();
    }

    /** Java 快照需要显式给出关系未知或已确认其他线程。 */
    @Test
    void rejectsMissingJavaThreadRelation() {
        var thread = new CrashDiagnostics.OtherThread("2", "worker", "RUNNABLE", 5, false, false, false,
                List.of(), null, null);
        var threads = new CrashDiagnostics.OtherThreads("ok", null, false, 10L, 1, 0, List.of(thread));
        var diagnostics = new CrashDiagnostics(null, null, threads, null, null, null, null, null, null);
        assertThat(validate(crash("arm64-v8a", "0x0", "module", "0x1"), diagnostics)).isNotEmpty();
    }

    /** 加载地址和现场函数显示名变化不影响临时归组，应用构建和 ABI 必须隔离。 */
    @Test
    void groupsByBuildSignalModuleAndRelativePc() {
        CrashFingerprintService fingerprints = new CrashFingerprintService();
        UUID app = UUID.fromString("11111111-1111-4111-8111-111111111111");
        NativeCrash original = crash("arm64-v8a", "0x123", "module", "0x1");
        NativeCrash changed = new NativeCrash(original.abi(), original.systemTid(), "other", true,
                new NativeCrash.Signal(11, "SIGSEGV", 1, null, "0xffff", "different"),
                new NativeCrash.Registers("ok", null, false, 20L, Map.of("pc", "0xffff")),
                List.of(new NativeCrash.Module("module", "renamed.so", "/other", original.abi(), "abcdef12")),
                new NativeCrash.Backtrace("ok", null, false, 20L,
                        List.of(new NativeCrash.Frame(0, "module", "0x1", "different", "0x99"))), null);
        String first = fingerprints.nativeTemporary(app, "com.example.app", "build-1", original);
        assertThat(fingerprints.nativeTemporary(app, "com.example.app", "build-1", changed)).isEqualTo(first);
        assertThat(fingerprints.nativeTemporary(app, "com.example.app", "build-2", original)).isNotEqualTo(first);
        assertThat(fingerprints.nativeTemporary(app, "com.example.app", "build-1",
                crash("x86", "0x123", "module", "0x1"))).isNotEqualTo(first);
        assertThat(fingerprints.nativeTemporary(app, "com.example.app", "build-1",
                crash("arm64-v8a", "0x123", "module", "0x2"))).isNotEqualTo(first);
        NativeCrash empty = new NativeCrash(original.abi(), original.systemTid(), null, null, original.signal(),
                original.registers(), List.of(), new NativeCrash.Backtrace("unavailable", "io_error", false, null, List.of()), null);
        assertThat(fingerprints.nativeTemporary(app, "com.example.app", "build-1", empty)).isNotEqualTo(first);
    }

    /** 组装无敏感数据的最小合法现场。 */
    private NativeCrash crash(String abi, String pc, String moduleId, String relativePc) {
        return new NativeCrash(abi, 7, "worker", false,
                new NativeCrash.Signal(11, "SIGSEGV", 1, "SEGV_MAPERR", "0x0", null),
                new NativeCrash.Registers("ok", null, false, 10L, Map.of("pc", pc)),
                List.of(new NativeCrash.Module("module", "libsample.so", null, abi, "abcdef12")),
                new NativeCrash.Backtrace("ok", null, false, 10L,
                        List.of(new NativeCrash.Frame(0, moduleId, relativePc, "entry", "0x0"))), null);
    }

    /** 返回全部固定类别的校验问题。 */
    private List<ValidationIssue> validate(NativeCrash crash, CrashDiagnostics diagnostics) {
        List<ValidationIssue> issues = new ArrayList<>();
        validator.validate(crash, diagnostics, issues);
        return issues;
    }
}
