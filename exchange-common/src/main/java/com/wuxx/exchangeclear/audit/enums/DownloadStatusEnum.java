package com.wuxx.exchangeclear.audit.enums;

import lombok.Getter;

@Getter
public enum DownloadStatusEnum {

    SUCCESS("SUCCESS", "下载成功"),
    FAILED("FAILED", "下载失败"),
    DENIED("DENIED", "权限拒绝"),
    TOKEN_INVALID("TOKEN_INVALID", "Token无效"),
    TOKEN_EXPIRED("TOKEN_EXPIRED", "Token过期"),
    LIMITED("LIMITED", "请求被限流"),
    FILE_NOT_FOUND("FILE_NOT_FOUND", "文件不存在"),
    FILE_NOT_PUBLISHED("FILE_NOT_PUBLISHED", "文件未发布"),
    FILE_REVOKED("FILE_REVOKED", "文件已撤销"),
    FILE_REISSUED("FILE_REISSUED", "文件已重发");

    private final String code;

    private final String desc;

    DownloadStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
