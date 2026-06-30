package com.wuxx.exchangeclear.download.audit;

import com.wuxx.exchangeclear.file.dto.FileMetadataDTO;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class AuditContext {

    private String requestId;

    private String fileNo;

    private String fileName;

    private String fileType;

    private LocalDate settleDate;

    private String memberId;

    private Integer version;

    private String clientIp;

    private String userAgent;

    private String tokenDigest;

    private Long fileSize;

    private LocalDateTime startTime;

    public void fillFileInfo(FileMetadataDTO file) {
        if (file == null) {
            return;
        }
        this.fileNo = file.getFileNo();
        this.fileName = file.getFileName();
        this.fileType = file.getFileType();
        this.settleDate = file.getSettleDate();
        this.memberId = file.getMemberId();
        this.version = file.getVersion();
        this.fileSize = file.getFileSize();
    }
}
