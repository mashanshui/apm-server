package com.shanshui.apmserver.anr.internal.web;

import com.shanshui.apmserver.anr.api.AnrEventDetailResponse;
import com.shanshui.apmserver.anr.api.AnrEventListResponse;
import com.shanshui.apmserver.anr.api.AnrOverviewResponse;
import com.shanshui.apmserver.anr.internal.application.AnrQueryService;
import com.shanshui.apmserver.identity.api.AppAccessControl;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 应用成员可访问的 ANR 查询入口。 */
@RestController
@RequestMapping("/api/v1/apps/{appId}/anrs")
public class AnrController {
    /** ANR 查询服务。 */
    private final AnrQueryService query;
    /** 应用成员查看权限。 */
    private final AppAccessControl access;

    /** 绑定查询和授权服务。 */
    public AnrController(AnrQueryService query, AppAccessControl access) {
        this.query = query;
        this.access = access;
    }

    /** 查询同一筛选下的事件数和设备数。 */
    @GetMapping("/overview")
    public AnrOverviewResponse overview(@PathVariable UUID appId,
                                        @RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to,
                                        @RequestParam(required = false) String appVersion,
                                        @RequestParam(required = false) String source,
                                        Authentication authentication) {
        access.requireView(appId, authentication);
        return query.overview(appId, from, to, appVersion, source);
    }

    /** 查询有限事件摘要页。 */
    @GetMapping("/events")
    public AnrEventListResponse events(@PathVariable UUID appId,
                                       @RequestParam(required = false) String from,
                                       @RequestParam(required = false) String to,
                                       @RequestParam(required = false) String appVersion,
                                       @RequestParam(required = false) String source,
                                       @RequestParam(required = false) Integer limit,
                                       @RequestParam(required = false) String cursor,
                                       @RequestParam(required = false) Long timeoutMs,
                                       Authentication authentication) {
        access.requireView(appId, authentication);
        return query.events(appId, from, to, appVersion, source, limit, cursor, timeoutMs);
    }

    /** 单独读取授权事件的原始现场，并禁止缓存。 */
    @GetMapping("/events/{eventId}")
    public ResponseEntity<AnrEventDetailResponse> event(@PathVariable UUID appId, @PathVariable String eventId,
                                                        Authentication authentication) {
        access.requireView(appId, authentication);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).header("Pragma", "no-cache")
                .body(query.event(appId, eventId));
    }
}
