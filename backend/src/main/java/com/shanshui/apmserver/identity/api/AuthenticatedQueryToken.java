package com.shanshui.apmserver.identity.api;

import java.util.UUID;

/** 独立于网页用户的应用只读查询身份。 */
public record AuthenticatedQueryToken(UUID tokenId, UUID appId, String scope) {
}
