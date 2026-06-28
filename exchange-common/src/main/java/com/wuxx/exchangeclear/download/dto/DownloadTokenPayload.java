package com.wuxx.exchangeclear.download.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DownloadTokenPayload {

    private String token;

    private String fileNo;

    private String memberId;

    private String clientIp;

    private LocalDateTime expireAt;

    private Boolean oneTime;
}
