package com.shanshui.apmserver.crash.internal.port;

import com.shanshui.apmserver.crash.internal.domain.CrashStoredSignal;
import com.shanshui.apmserver.telemetry.api.AppendResult;

import java.util.List;

/** Crash/app_start 幂等写入端口。 */
public interface CrashWritePort {

    AppendResult append(java.util.UUID appId, List<CrashStoredSignal> events);
}
