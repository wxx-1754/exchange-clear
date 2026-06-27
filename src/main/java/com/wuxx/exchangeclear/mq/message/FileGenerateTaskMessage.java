package com.wuxx.exchangeclear.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileGenerateTaskMessage {

    private String messageId;

    private String taskNo;

    private LocalDate settleDate;

    private String memberId;

    private String fileType;

    private Integer version;

    private Long createdAt;
}
