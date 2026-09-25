package com.chaoxing.mcpserver.common.result;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一请求/响应包装。
 * <p>
 * 所有 MCP 工具方法（以及将来暴露给前端的 API）统一返回本类型，
 * 调用方只需看 {@code success} + {@code code} + {@code data} 三个字段，
 * 底层数据库/网络/业务异常的细节由 {@link com.chaoxing.mcpserver.common.exception.ErrorCode}
 * 收敛，不直接暴露 {@code SQLException} 的 SQLState/厂商错误码。
 * </p>
 *
 * @param <T> 业务数据类型
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> {

    /** 成功（code=0）。 */
    public static final int SUCCESS_CODE = 0;

    /** 是否成功。 */
    private boolean success;
    /** 业务错误码（见 {@link com.chaoxing.mcpserver.common.exception.ErrorCode}；0=成功）。 */
    private int code;
    /** 人类可读信息（不暴露 SQL 文本、堆栈等敏感细节）。 */
    private String message;
    /** 业务数据（失败时通常为 null）。 */
    private T data;

    protected Result() {
    }

    private Result(boolean success, int code, String message, T data) {
        this.success = success;
        this.code = code;
        this.message = message;
        this.data = data;
    }

    // ------------------------------------------------------------------
    // 工厂方法
    // ------------------------------------------------------------------

    /** 成功（无数据）。 */
    public static <T> Result<T> ok() {
        return new Result<>(true, SUCCESS_CODE, null, null);
    }

    /** 成功（带数据）。 */
    public static <T> Result<T> ok(T data) {
        return new Result<>(true, SUCCESS_CODE, null, data);
    }

    /** 成功（带数据 + 提示信息）。 */
    public static <T> Result<T> ok(T data, String message) {
        return new Result<>(true, SUCCESS_CODE, message, data);
    }

    /** 失败（指定错误码 + 安全信息，data 为 null）。 */
    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(false, code, message, null);
    }

    /** 失败（带部分数据 + 错误码 + 安全信息）。 */
    public static <T> Result<T> fail(int code, String message, T data) {
        return new Result<>(false, code, message, data);
    }

    /** 由 {@link com.chaoxing.mcpserver.common.exception.DbException} 构造失败响应。 */
    public static <T> Result<T> fromDbException(com.chaoxing.mcpserver.common.exception.DbException e) {
        return fail(e.getCode(), e.getSafeMessage());
    }

    // ------------------------------------------------------------------
    // 序列化辅助（MCP 工具方法返回 String 时直接用）
    // ------------------------------------------------------------------

    /** 转为扁平 Map（便于 Jackson 序列化为 JSON 字符串）。 */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", success);
        m.put("code", code);
        if (message != null) {
            m.put("message", message);
        }
        if (data != null) {
            m.put("data", data);
        }
        return m;
    }

    // ------------------------------------------------------------------
    // 访问器
    // ------------------------------------------------------------------

    public boolean isSuccess() {
        return success;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public void setData(T data) {
        this.data = data;
    }

    @Override
    public String toString() {
        return "Result{success=" + success + ", code=" + code + ", message=" + message + ", data=" + data + "}";
    }
}
