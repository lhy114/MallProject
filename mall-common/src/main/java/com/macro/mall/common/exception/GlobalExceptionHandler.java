package com.macro.mall.common.exception;

import cn.hutool.core.util.StrUtil;
import com.macro.mall.common.api.BusinessErrorCode;
import com.macro.mall.common.api.CommonResult;
import com.macro.mall.common.api.IErrorCode;
import com.macro.mall.common.api.ResultCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;

import java.sql.SQLSyntaxErrorException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理类
 * Created by macro on 2020/2/27.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    @ResponseBody
    @ExceptionHandler(value = ApiException.class)
    public ResponseEntity<CommonResult<?>> handle(ApiException e) {
        IErrorCode errorCode = e.getErrorCode();
        if (errorCode == null) {
            return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(CommonResult.failed(e.getMessage()));
        }

        CommonResult<?> result = CommonResult.failed(
            errorCode.getCode(),
            errorCode.getMessage(),
            e.getDetails()
        );
        return ResponseEntity
            .status(resolveHttpStatus(errorCode))
            .body(result);
    }

    private HttpStatus resolveHttpStatus(IErrorCode errorCode) {
        if (errorCode instanceof BusinessErrorCode) {
            return HttpStatus.valueOf(
                ((BusinessErrorCode) errorCode).getHttpStatus()
            );
        }
        if (errorCode == ResultCode.UNAUTHORIZED) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (errorCode == ResultCode.FORBIDDEN) {
            return HttpStatus.FORBIDDEN;
        }
        if (errorCode == ResultCode.VALIDATE_FAILED) {
            return HttpStatus.BAD_REQUEST;
        }
        if (errorCode == ResultCode.FAILED) {
            return HttpStatus.BAD_REQUEST;
        }
        return HttpStatus.BAD_REQUEST;
    }

    @ResponseBody
    @ExceptionHandler(value = MethodArgumentNotValidException.class)
    public ResponseEntity<CommonResult<?>> handleValidException(MethodArgumentNotValidException e) {
        Map<String, String> errors = getFieldErrors(e.getBindingResult());
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(CommonResult.failed(BusinessErrorCode.PARAM_INVALID, errors));
    }

    @ResponseBody
    @ExceptionHandler(value = BindException.class)
    public ResponseEntity<CommonResult<?>> handleValidException(BindException e) {
        Map<String, String> errors = getFieldErrors(e.getBindingResult());
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(CommonResult.failed(BusinessErrorCode.PARAM_INVALID, errors));
    }

    @ResponseBody
    @ExceptionHandler(value = ConstraintViolationException.class)
    public ResponseEntity<CommonResult<?>> handleConstraintViolationException(ConstraintViolationException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : e.getConstraintViolations()) {
            errors.put(violation.getPropertyPath().toString(), violation.getMessage());
        }
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(CommonResult.failed(BusinessErrorCode.PARAM_INVALID, errors));
    }

    private Map<String, String> getFieldErrors(BindingResult bindingResult) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : bindingResult.getFieldErrors()) {
            errors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return errors;
    }

    @ResponseBody
    @ExceptionHandler(value = SQLSyntaxErrorException.class)
    public ResponseEntity<CommonResult<?>> handleSQLSyntaxErrorException(SQLSyntaxErrorException e) {
        String message = e.getMessage();
        if (StrUtil.isNotEmpty(message) && message.contains("denied")) {
            message = "演示环境暂无修改权限，如需修改数据可本地搭建后台服务！";
        } else {
            message = BusinessErrorCode.SYSTEM_ERROR.getMessage();
        }
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(CommonResult.failed(
                BusinessErrorCode.SYSTEM_ERROR.getCode(),
                message,
                null
            ));
    }
}
