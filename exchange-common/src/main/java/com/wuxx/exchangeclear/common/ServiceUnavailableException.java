package com.wuxx.exchangeclear.common;

public class ServiceUnavailableException extends BizException {

    public ServiceUnavailableException(Integer code, String message) {
        super(code, message);
    }
}
