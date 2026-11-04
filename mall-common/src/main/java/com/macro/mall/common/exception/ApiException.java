package com.macro.mall.common.exception;

import com.macro.mall.common.api.IErrorCode;

/**
 * 自定义API异常
 * Created by macro on 2020/2/27.
 */
public class ApiException extends RuntimeException {
    private final IErrorCode errorCode;
    private final Object details;

    public ApiException(IErrorCode errorCode) {
        this(errorCode, null);
    }

    public ApiException(IErrorCode errorCode, Object details) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.details = details;
    }

    public ApiException(String message) {
        super(message);
        this.errorCode = null;
        this.details = null;
    }

    public ApiException(Throwable cause) {
        super(cause);
        this.errorCode = null;
        this.details = null;
    }

    public ApiException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = null;
        this.details = null;
    }

    public IErrorCode getErrorCode() {
        return errorCode;
    }

    public Object getDetails() {
        return details;
    }
}
