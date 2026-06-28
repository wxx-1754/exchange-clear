package com.wuxx.exchangeclear.enums;

import com.wuxx.exchangeclear.common.BizException;
import lombok.Getter;

import java.util.Arrays;

@Getter
public enum FileTypeEnum {

    TRADE("TRADE", "成交文件");

    private final String code;

    private final String desc;

    FileTypeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static FileTypeEnum require(String code) {
        return Arrays.stream(values())
                .filter(item -> item.getCode().equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new BizException("不支持的文件类型：" + code));
    }
}
