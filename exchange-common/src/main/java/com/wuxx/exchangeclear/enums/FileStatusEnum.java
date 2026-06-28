package com.wuxx.exchangeclear.enums;

import lombok.Getter;

@Getter
public enum FileStatusEnum {

    GENERATED("GENERATED", "已生成，待发布"),
    PUBLISHED("PUBLISHED", "已发布，可下载"),
    REVOKED("REVOKED", "已撤销，不可下载"),
    REISSUED("REISSUED", "已被新版本替代，不可下载"),
    FAILED("FAILED", "生成失败");

    private final String code;

    private final String desc;

    FileStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static FileStatusEnum require(String code) {
        for (FileStatusEnum status : values()) {
            if (status.code.equalsIgnoreCase(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("不支持的文件状态：" + code);
    }
}
