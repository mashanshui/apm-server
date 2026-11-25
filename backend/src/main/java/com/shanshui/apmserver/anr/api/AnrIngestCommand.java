package com.shanshui.apmserver.anr.api;

import java.util.Map;

/** 批次接收向 ANR 领域传递的原始事件。 */
public record AnrIngestCommand(
        /** 协议版本。 */ Integer schemaVersion,
        /** 客户端冻结的事件标识。 */ String eventId,
        /** 事件类型。 */ String eventType,
        /** 原发生时间。 */ Long occurredAt,
        /** 原运行会话标识。 */ String sessionId,
        /** 原进程实例标识。 */ String processId,
        /** 匿名设备标识。 */ String anonymousDeviceId,
        /** 包名。 */ String packageName,
        /** 应用版本。 */ String appVersion,
        /** 版本编号。 */ Integer versionCode,
        /** 构建标识。 */ String buildId,
        /** 环境。 */ String environment,
        /** 渠道。 */ String channel,
        /** 系统版本。 */ String osVersion,
        /** 设备型号。 */ String deviceModel,
        /** 网络类别。 */ String networkType,
        /** 公共数值字段。 */ Map<String, Object> measurements,
        /** 公共扩展字段。 */ Map<String, Object> attributes,
        /** ANR 专属载荷。 */ AnrPayload anr) { }
