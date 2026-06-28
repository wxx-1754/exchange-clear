package com.wuxx.exchangeclear.enums;

import lombok.Getter;

@Getter
public enum FileStatusEnum {

    GENERATED("GENERATED", "已生成"),
    FAILED("FAILED", "生成失败");

    private final String code;

    private final String desc;

    FileStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
