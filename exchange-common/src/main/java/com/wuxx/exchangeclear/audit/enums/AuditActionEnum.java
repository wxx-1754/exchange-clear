package com.wuxx.exchangeclear.audit.enums;

import lombok.Getter;

@Getter
public enum AuditActionEnum {

    CREATE_TOKEN("CREATE_TOKEN", "创建下载Token"),
    DOWNLOAD_FILE("DOWNLOAD_FILE", "下载文件");

    private final String code;

    private final String desc;

    AuditActionEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
