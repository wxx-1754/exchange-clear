package com.wuxx.exchangeclear.common;

public class RateLimitException extends BizException {

    public RateLimitException(Integer code, String message) {
        super(code, message);
    }

    public RateLimitException(String message) {
        super(429, message);
    }
}
