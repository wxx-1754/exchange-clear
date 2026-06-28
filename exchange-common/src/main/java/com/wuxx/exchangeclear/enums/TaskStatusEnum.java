package com.wuxx.exchangeclear.enums;

import lombok.Getter;

@Getter
public enum TaskStatusEnum {

    INIT("INIT", "待投递"),
    SENT("SENT", "已投递"),
    GENERATING("GENERATING", "生成中"),
    GENERATED("GENERATED", "已生成"),
    FAILED("FAILED", "生成失败"),
    SEND_FAILED("SEND_FAILED", "消息发送失败");

    private final String code;

    private final String desc;

    TaskStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static TaskStatusEnum require(String code) {
        for (TaskStatusEnum status : values()) {
            if (status.code.equalsIgnoreCase(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("不支持的任务状态：" + code);
    }
}
