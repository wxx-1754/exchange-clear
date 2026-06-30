package com.wuxx.exchangeclear.audit.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AbnormalDownloadStatsDTO {

    private Long tokenInvalidCount = 0L;

    private Long tokenExpiredCount = 0L;

    private Long deniedCount = 0L;

    private Long limitedCount = 0L;

    private Long fileNotPublishedCount = 0L;

    private Long fileRevokedCount = 0L;

    private Long fileReissuedCount = 0L;

    private List<DownloadRankDTO> topIps = new ArrayList<>();

    private List<DownloadRankDTO> topMembers = new ArrayList<>();
}
