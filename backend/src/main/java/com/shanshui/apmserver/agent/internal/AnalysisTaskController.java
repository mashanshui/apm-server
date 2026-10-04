package com.shanshui.apmserver.agent.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 网页单事件任务入口；执行器与凭据写回不走此 Session 接口。 */
@RestController
@RequestMapping("/api/v1/apps/{appId}")
public class AnalysisTaskController {
    /** 停止事实的管理员核验服务。 */
    private final AnalysisRunService runs;
    /** 单事件任务业务服务。 */
    private final AnalysisTaskService tasks;

    /** 注入任务服务。 */
    public AnalysisTaskController(AnalysisTaskService tasks, AnalysisRunService runs) { this.tasks = tasks; this.runs = runs; }

    /** 请求只包含稳定幂等键，事件及模型从路径和服务端推导。 */
    public record CreateRequest(/** 客户端重试复用此键。 */ @NotNull UUID idempotencyKey) {}

    /** 按单事件发起，Developer 可以创建当前代码任务。 */
    @PostMapping("/crashes/events/{eventId}/analyses")
    public ResponseEntity<AnalysisTaskView> create(@PathVariable UUID appId,
            @PathVariable @Size(min = 1, max = 128) String eventId, @Valid @RequestBody CreateRequest request,
            Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tasks.create(appId, eventId, request.idempotencyKey(), authentication));
    }

    /** 不读取同 Issue 其他事件。 */
    @GetMapping("/crashes/events/{eventId}/analyses")
    public ResponseEntity<List<AnalysisTaskView>> list(@PathVariable UUID appId, @PathVariable String eventId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tasks.list(appId, eventId, authentication, page, size));
    }

    /** 成员查看指定任务的固定输入摘要与执行状态。 */
    @GetMapping("/analysis-tasks/{taskId}")
    public ResponseEntity<AnalysisTaskView> get(@PathVariable UUID appId, @PathVariable UUID taskId, Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tasks.get(appId, taskId, authentication));
    }

    /** 补齐前提后显式准备，不后台自动调用模型。 */
    @PostMapping("/analysis-tasks/{taskId}/recheck")
    public ResponseEntity<AnalysisTaskView> recheck(@PathVariable UUID appId, @PathVariable UUID taskId, Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tasks.recheck(appId, taskId, authentication));
    }
    /** 取消由创建者或管理员发起，运行环境必须再确认停止。 */
    @PostMapping("/analysis-tasks/{taskId}/cancel")
    public ResponseEntity<AnalysisTaskView> cancel(@PathVariable UUID appId, @PathVariable UUID taskId, Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tasks.cancel(appId, taskId, authentication));
    }

    /** 显式失败重试保留 Run 历史，停止未确认时拒绝。 */
    @PostMapping("/analysis-tasks/{taskId}/retry")
    public ResponseEntity<AnalysisTaskView> retry(@PathVariable UUID appId, @PathVariable UUID taskId, Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tasks.retry(appId, taskId, authentication));
    }

    /** 只有人工环境检查的依据，不能由模型填充。 */
    public record StopVerification(/** 具体环境及检查过程。 */ @jakarta.validation.constraints.NotBlank @Size(max=2000) String basis) {}

    /** 管理员替代已撤销或失联 Worker 留下停止核验记录。 */
    @PostMapping("/analysis-runs/{runId}/confirm-stopped")
    public ResponseEntity<Void> confirmStopped(@PathVariable UUID appId, @PathVariable UUID runId,
            @Valid @RequestBody StopVerification request, Authentication authentication) {
        runs.confirmStopped(appId, runId, authentication, request.basis());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /** 分页展示每次尝试，内容到期与执行失败分别保留。 */
    @GetMapping("/analysis-tasks/{taskId}/runs")
    public ResponseEntity<List<AnalysisRunService.RunDetail>> history(@PathVariable UUID appId, @PathVariable UUID taskId,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size, Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(runs.history(appId, taskId, authentication, page, size));
    }

}
