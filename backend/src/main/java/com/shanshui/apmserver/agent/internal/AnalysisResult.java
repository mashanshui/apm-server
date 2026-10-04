package com.shanshui.apmserver.agent.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

/** 当前工作区报告；版本 4 新写入，旧报告 JSON 仅作历史读取。 */
public record AnalysisResult(
        /** 当前唯一写入版本。 */ @Min(4) @Max(4) int schemaVersion,
        /** 固定事件证据。 */ @NotNull UUID evidenceId,
        /** 当前服务端分配。 */ @NotNull UUID runId,
        /** 候选不表示历史 APK 根因已证明。 */ @NotBlank @Pattern(regexp="ROOT_CAUSE_CANDIDATE|INSUFFICIENT_EVIDENCE") String conclusion,
        /** 当前材料下的分析概述。 */ @NotBlank @Size(max=4000) String summary,
        /** 有界候选及事件引用。 */ @NotNull @Size(max=5) List<@NotNull @Valid Candidate> candidates,
        /** 宿主提供且工具提交时核对的引用。 */ @NotNull @Size(max=20) List<@NotNull @Valid SourceReference> sourceRefs,
        /** 历史差异及缺失输入。 */ @NotNull @Size(max=20) List<@NotBlank @Size(max=1000) String> unknowns,
        /** 当前修复风险。 */ @NotNull @Size(max=20) List<@NotBlank @Size(max=1000) String> risks,
        /** 建议与实际修改分开。 */ @NotNull @Size(max=20) List<@NotBlank @Size(max=1000) String> fixSuggestions,
        /** 建议与已执行验证分开。 */ @NotNull @Size(max=20) List<@NotBlank @Size(max=1000) String> validationSuggestions,
        /** 工具执行来源，宿主名称只作自报。 */ @NotNull @Valid Execution execution,
        /** 无可信宿主计量时所有值保持未知。 */ @NotNull @Valid Usage usage,
        /** 宿主自报修改事实，不是 Python 写入证明。 */ @NotNull @Valid Repair repair,
        /** 宿主实际命令的有限自报结果。 */ @NotNull @Valid Verification verification) {

    /** 候选必须属于当前事件的证据片段。 */
    public record Candidate(/** 候选标题。 */ @NotBlank @Size(max=500) String title,
            /** 推断与局限。 */ @NotBlank @Size(max=4000) String reason,
            /** 服务端片段 ID。 */ @NotNull @Size(min=1,max=20) List<@NotBlank @Size(max=64) String> evidenceRefs) {}

    /** 宿主使用的源码相对位置，不能包含本机路径。 */
    public record SourceReference(/** 项目相对路径。 */ @NotBlank @Size(max=512) String path,
            /** 一起始行。 */ @Min(1) int startLine, /** 含结束行。 */ @Min(1) int endLine,
            /** 宿主提供的脱敏源码，LF 拼接。 */ @NotNull @Size(max=8192) String snippet,
            /** 展示片段的 UTF-8 摘要。 */ @NotBlank @Pattern(regexp="[a-f0-9]{64}") String snippetSha256,
            /** 宿主提供片段，不能证明此前实际读取。 */ @NotBlank @Pattern(regexp="HOST_REPORTED") String metadataSource,
            /** Python 提交时当前位置匹配，不能证明历史版本。 */ @NotBlank @Pattern(regexp="CURRENT_MATCH|CURRENT_DIFFERENT|UNAVAILABLE") String currentCheck) {}

    /** 工具可核验版本，模型及宿主来源不能冒充平台验证。 */
    public record Execution(/** 不可核验的提供方。 */ @Null String providerId,
            /** 不可核验的模型。 */ @Null String modelId,
            /** 当前固定模式。 */ @NotBlank @Pattern(regexp="HOST_AGENT") String mode,
            /** 宿主自报名称。 */ @Size(max=100) String host,
            /** 宿主自报版本。 */ @Size(max=100) String hostVersion,
            /** 当前 Python 工具版本。 */ @NotBlank @Pattern(regexp="0\\.4\\.0") String toolVersion,
            /** 来源属性。 */ @NotBlank @Pattern(regexp="HOST_REPORTED|UNKNOWN") String metadataSource) {}

    /** 当前无可信用量和费用通道，不能把未知填写为零。 */
    public record Usage(/** 输入未知。 */ @Null Long inputTokens, /** 输出未知。 */ @Null Long outputTokens,
            /** 缓存读取未知。 */ @Null Long cacheReadTokens, /** 缓存写入未知。 */ @Null Long cacheWriteTokens,
            /** 费用未知。 */ @Null Double cost) {}

    /** 修改事实独立于报告保存与测试结果。 */
    public record Repair(/** 实际修改状态。 */ @NotBlank @Pattern(regexp="NOT_REQUESTED|NOT_APPLICABLE|APPLIED|PARTIAL|CONFLICT|FAILED") String status,
            /** 实际写入文件，最多二十个。 */ @NotNull @Size(max=20) List<@NotNull @Valid Edit> files,
            /** 未修改或部分完成原因。 */ @NotNull @Size(max=2000) String reason,
            /** 修改事实只由宿主报告。 */ @NotBlank @Pattern(regexp="HOST_REPORTED") String metadataSource) {}

    /** 宿主描述修改文件，不伪造工具写前/写后摘要。 */
    public record Edit(/** 项目相对路径。 */ @NotBlank @Size(max=512) String path,
            /** 宿主提供的简短修改说明。 */ @NotNull @Size(max=1000) String summary) {}

    /** 自报验证结果不能代替独立 CI 或源码一致性。 */
    public record Verification(/** 命令整体结果。 */ @NotBlank @Pattern(regexp="PASSED|FAILED|NOT_RUN") String status,
            /** 只能是宿主报告。 */ @NotBlank @Pattern(regexp="HOST_REPORTED") String metadataSource,
            /** 有限命令，绝对路径和秘密需工具脱敏。 */ @NotNull @Size(max=10) List<@NotNull @Valid Command> commands,
            /** 未运行、失败或材料变化原因。 */ @NotNull @Size(max=2000) String reason) {}

    /** 仅保存命令事实及短摘要，不上传原始日志。 */
    public record Command(/** 相对路径或路径占位符命令。 */ @NotBlank @Size(max=1000) String command,
            /** 宿主实际退出码。 */ @NotNull Integer exitCode,
            /** 受限脱敏摘要。 */ @NotNull @Size(max=2000) String summary) {}
}
