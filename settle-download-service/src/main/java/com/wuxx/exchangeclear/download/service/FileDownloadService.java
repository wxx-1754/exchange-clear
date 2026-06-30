package com.wuxx.exchangeclear.download.service;

import com.wuxx.exchangeclear.audit.enums.DownloadStatusEnum;
import com.wuxx.exchangeclear.download.audit.AuditContext;
import com.wuxx.exchangeclear.download.audit.AuditStatusMapper;
import com.wuxx.exchangeclear.download.audit.DownloadAuditProducer;
import com.wuxx.exchangeclear.download.audit.DownloadAuditProperties;
import com.wuxx.exchangeclear.download.audit.TokenDigestUtils;
import com.wuxx.exchangeclear.download.token.DownloadTokenService;
import com.wuxx.exchangeclear.common.IdGenerator;
import com.wuxx.exchangeclear.file.client.FileMetadataClient;
import com.wuxx.exchangeclear.file.dto.FileMetadataDTO;
import com.wuxx.exchangeclear.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import javax.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileDownloadService {

    private final FileMetadataClient fileMetadataClient;

    private final ObjectStorageService objectStorageService;

    private final DownloadTokenService downloadTokenService;

    private final DownloadAuditProducer downloadAuditProducer;

    private final DownloadAuditProperties auditProperties;

    public void download(String fileNo,
                         String token,
                         String memberId,
                         String clientIp,
                         String requestId,
                         String userAgent,
                         HttpServletResponse response) {
        long startMillis = System.currentTimeMillis();
        AuditContext auditContext = auditContext(fileNo, token, memberId, clientIp, requestId, userAgent);
        FileMetadataDTO file = null;
        try {
            file = downloadTokenService.validateToken(fileNo, token, memberId, clientIp);
            auditContext.fillFileInfo(file);

            response.setContentType("application/octet-stream");
            response.setHeader("Content-Length", String.valueOf(file.getFileSize()));
            response.setHeader("Content-Disposition", contentDisposition(file.getFileName()));

            long downloadBytes;
            try (InputStream inputStream = objectStorageService.download(file.getStoragePath())) {
                downloadBytes = StreamUtils.copy(inputStream, response.getOutputStream());
                response.flushBuffer();
            } catch (Exception e) {
                throw new IllegalStateException("文件下载失败", e);
            }

            downloadAuditProducer.send(
                    auditContext, DownloadStatusEnum.SUCCESS, null, costMillis(startMillis), downloadBytes);
            increaseDownloadCount(fileNo);
        } catch (RuntimeException e) {
            if (file == null) {
                enrichFileInfo(auditContext, fileNo);
            }
            downloadAuditProducer.send(
                    auditContext, AuditStatusMapper.from(e), rootMessage(e), costMillis(startMillis), 0L);
            throw e;
        }
    }

    private AuditContext auditContext(String fileNo,
                                      String token,
                                      String memberId,
                                      String clientIp,
                                      String requestId,
                                      String userAgent) {
        AuditContext context = new AuditContext();
        context.setFileNo(fileNo);
        context.setMemberId(memberId);
        context.setClientIp(clientIp);
        context.setRequestId(StringUtils.hasText(requestId) ? requestId : IdGenerator.next("REQ"));
        context.setUserAgent(userAgent);
        context.setStartTime(LocalDateTime.now());
        if (auditProperties.isSaveTokenDigest()) {
            context.setTokenDigest(TokenDigestUtils.sha256(token));
        }
        return context;
    }

    private void enrichFileInfo(AuditContext auditContext, String fileNo) {
        try {
            auditContext.fillFileInfo(fileMetadataClient.getMetadata(fileNo));
        } catch (Exception e) {
            log.debug("[AUDIT] enrich audit file info failed, fileNo={}", fileNo, e);
        }
    }

    private void increaseDownloadCount(String fileNo) {
        try {
            fileMetadataClient.increaseDownloadCount(fileNo);
        } catch (Exception e) {
            log.warn("文件已发送，但更新下载次数失败, fileNo={}", fileNo, e);
        }
    }

    private long costMillis(long startMillis) {
        return System.currentTimeMillis() - startMillis;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private String contentDisposition(String fileName) {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + fileName + "\"; filename*=UTF-8''" + encoded;
    }
}
