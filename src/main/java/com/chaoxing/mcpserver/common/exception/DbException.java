package com.chaoxing.mcpserver.common.exception;

/**
 * 统一业务异常（数据库层）。
 * <p>
 * 底层 {@code SQLException}（SQLState、厂商错误码、堆栈、SQL 文本）不应直接暴露给
 * MCP 客户端或前端。{@link DbExceptionMapper} 负责把 {@code SQLException}
 * 归一化成本类型，业务层只需 {@code catch (DbException e)} 读取
 * {@link #code} + {@link #getSafeMessage()}，无需认识 SQLState。
 * </p>
 * <p>
 * 敏感信息脱敏：{@link #getSafeMessage()} 只携带 {@link ErrorCode#getDefaultMessage()}
 * （可定制），原始 SQL 文本 / 堆栈只写进 {@link #getCause()}（日志用），
 * 绝不出现在对外响应里。
 * </p>
 */
public class DbException extends RuntimeException {

    /** 业务错误码（对应 {@link ErrorCode#getCode()}）。 */
    private final int code;

    /** 对外的安全信息（已脱敏，无 SQL 文本/堆栈）。 */
    private final String safeMessage;

    public DbException(int code, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.code = code;
        this.safeMessage = safeMessage;
    }

    public DbException(ErrorCode errorCode, Throwable cause) {
        this(errorCode.getCode(), errorCode.getDefaultMessage(), cause);
    }

    public int getCode() {
        return code;
    }

    /** 可安全暴露给调用方的信息（不含 SQL 文本 / SQLState / 堆栈）。 */
    public String getSafeMessage() {
        return safeMessage;
    }

    /** 用指定信息构造新的 DbException（同 code，用于定制对外文案）。 */
    public DbException withSafeMessage(String newSafeMessage) {
        return new DbException(this.code, newSafeMessage, this.getCause());
    }

    @Override
    public String toString() {
        // 日志用，可带 cause；对外响应请用 getSafeMessage()
        String base = "DbException{code=" + code + ", safeMessage=" + safeMessage;
        if (getCause() != null) {
            base += ", cause=" + getCause().getClass().getSimpleName();
        }
        return base + "}";
    }
}
