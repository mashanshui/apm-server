package com.shanshui.apmserver.identity.internal.domain;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;

/** PATCH 字段存在性 DTO；省略保留，显式 null 交给领域校验。 */
public final class AppUpdateRequest {
    /** 提交名称，可能为 null，但已出现的 null 不合法。 */
    private String name;
    /** 提交描述，null 表示清空。 */
    private String description;
    /** 是否在 JSON 中提交名称。 */
    private boolean namePresent;
    /** 是否在 JSON 中提交描述。 */
    private boolean descriptionPresent;

    /** 使用 Object 接收原类型，拒绝 Jackson 的数字/布尔到字符串强制转换。 */
    @JsonSetter("name")
    public void setName(Object value) {
        name = stringOrNull(value);
        namePresent = true;
    }
    /** 描述允许显式 null，仍需记录字段出现。 */
    @JsonSetter("description")
    public void setDescription(Object value) {
        description = stringOrNull(value);
        descriptionPresent = true;
    }
    /** 返回已提交名称。 */
    public String name() { return name; }
    /** 返回已提交描述。 */
    public String description() { return description; }
    /** 判断名称是否应被更新。 */
    public boolean namePresent() { return namePresent; }
    /** 判断描述是否应被更新。 */
    public boolean descriptionPresent() { return descriptionPresent; }
    /** 身份与未知字段均拒绝，不在异常中包含输入名称或值。 */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("应用更新请求包含不可修改或未知字段");
    }
    /** DTO 只接受字符串/null，不改变领域规范化口径。 */
    private String stringOrNull(Object value) {
        if (value != null && !(value instanceof String)) {
            throw new IllegalArgumentException("应用更新字段必须是字符串或 null");
        }
        return (String) value;
    }
}
