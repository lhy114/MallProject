package com.macro.mall.common.api;

/**
 * 业务错误码。
 */
public enum BusinessErrorCode implements IErrorCode {
    PARAM_INVALID(40001, 400, "参数校验失败"),
    UNAUTHORIZED(40101, 401, "暂未登录或token已经过期"),
    FORBIDDEN(40301, 403, "没有相关权限"),
    NOT_FOUND(40401, 404, "请求的数据不存在"),
    DATA_CONFLICT(40901, 409, "数据状态冲突，无法完成操作"),
    BUSINESS_ERROR(40000, 400, "业务处理失败"),
    SYSTEM_ERROR(50001, 500, "系统内部错误");

    private final long code;
    private final int httpStatus;
    private final String message;

    BusinessErrorCode(long code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    @Override
    public long getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
