package com.wuxx.exchangeclear.generator;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Data
@Builder
public class FileGenerateContext {

    private String taskNo;

    private LocalDate settleDate;

    private String memberId;

    private String fileType;

    private Integer version;
}
