package com.shanshui.apmserver.agent.internal;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.http.MediaType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 独立执行入口，归属应用只取已认证 Worker 身份，不接受调用方扩权。 */
@RestController
@RequestMapping("/api/worker/v1/tasks")
public class WorkerTaskController {
    /** 单事件任务服务。 */
    private final AnalysisTaskService tasks;

    /** 原子分配及租约服务。 */
    private final AnalysisRunService runs;

    /** 注入任务及当前分配管理。 */
    public WorkerTaskController(AnalysisTaskService tasks, AnalysisRunService runs) { this.tasks = tasks; this.runs = runs; }

    /** 网络重试必须复用同一请求 ID。 */
    public record ClaimRequest(/** 稳定 UUID。 */ @NotNull UUID requestId) {}

    /** 不扫描待执行队列，只领取明确指定的任务。 */
    @PostMapping("/{taskId}/claim")
    public ResponseEntity<AnalysisRunService.Claimed> claim(@AuthenticationPrincipal WorkerCredentialService.Identity identity,
            @PathVariable UUID taskId, @Valid @RequestBody ClaimRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(runs.claim(identity, taskId, request.requestId()));
    }

    /** 对账读取原 Run，不提供秘密或其他凭据的分配。 */
    @GetMapping("/runs/{runId}")
    public ResponseEntity<AnalysisRunService.RunView> status(@AuthenticationPrincipal WorkerCredentialService.Identity identity, @PathVariable UUID runId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(runs.status(identity, runId));
    }

    /** 心跳仅续当前分配，取消状态返回停止意图。 */
    @PostMapping("/runs/{runId}/heartbeat")
    public ResponseEntity<AnalysisRunService.RunView> heartbeat(@AuthenticationPrincipal WorkerCredentialService.Identity identity,
            @PathVariable UUID runId, @RequestBody AnalysisRunService.Lease lease) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(runs.heartbeat(identity, runId, lease));
    }

    /** 秘密通过请求头传递，正文严格返回服务端冻结的原始 JSON 字节。 */
    @GetMapping("/runs/{runId}/evidence")
    public ResponseEntity<String> evidence(@AuthenticationPrincipal WorkerCredentialService.Identity identity, @PathVariable UUID runId,
            @RequestHeader("X-Analysis-Lease") String token, @RequestHeader("X-Analysis-Generation") long generation) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON)
                .body(runs.evidence(identity, runId, new AnalysisRunService.Lease(generation, token)));
    }

    /** 领取前的元数据不包含证据正文；后续正文读取必须绑定 Run。 */
    @GetMapping("/{taskId}")
    public ResponseEntity<AnalysisTaskService.Inspection> inspect(@AuthenticationPrincipal WorkerCredentialService.Identity identity,
            @PathVariable UUID taskId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tasks.inspect(identity, taskId));
    }
    /** 回执只确认本任务工具关闭，不能替代宿主停止事实。 */
    public record StopRequest(/** 原分配租约。 */ @NotNull AnalysisRunService.Lease lease, /** 稳定终止原因。 */ @NotNull String errorCode) {}

    /** 本任务工具关闭后的幂等回执，宿主停止仍未知。 */
    @PostMapping("/runs/{runId}/stopped")
    public ResponseEntity<AnalysisRunService.RunView> stopped(@AuthenticationPrincipal WorkerCredentialService.Identity identity,
            @PathVariable UUID runId, @Valid @RequestBody StopRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(runs.stopped(identity, runId, request.lease(), request.errorCode()));
    }

    /** 结构化结果回传，摘要固定便于未知响应对账；宿主停止另行核验。 */
    @PostMapping("/runs/{runId}/complete")
    public ResponseEntity<AnalysisRunService.RunView> complete(@AuthenticationPrincipal WorkerCredentialService.Identity identity,
            @PathVariable UUID runId, @RequestBody AnalysisRunService.Completion request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(runs.complete(identity, runId, request));
    }

    /** 清理失败可单独报告失败及停止未确认，不能释放重试门禁。 */
    @PostMapping("/runs/{runId}/failure")
    public ResponseEntity<AnalysisRunService.RunView> failure(@AuthenticationPrincipal WorkerCredentialService.Identity identity,
            @PathVariable UUID runId, @RequestBody AnalysisRunService.Failure request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(runs.fail(identity, runId, request));
    }

}
