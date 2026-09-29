package com.shanshui.apmserver.crash.internal.domain;

import java.util.List;

/** Crash 查询端口返回的有界页面及下一页游标。 */
public record CrashPage<T>(List<T> items, String nextCursor) {
}
