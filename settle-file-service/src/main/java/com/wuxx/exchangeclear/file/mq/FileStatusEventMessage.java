package com.wuxx.exchangeclear.file.mq;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Data
@Builder
public class FileStatusEventMessage {

    private String messageId;

    private String eventType;

    private String fileNo;

    private LocalDate settleDate;

    private String memberId;

    private String fileType;

    private Integer version;

    private String operator;

    private Long eventTime;
}
