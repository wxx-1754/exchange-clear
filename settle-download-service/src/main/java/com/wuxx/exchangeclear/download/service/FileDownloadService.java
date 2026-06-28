package com.wuxx.exchangeclear.download.service;

import com.wuxx.exchangeclear.file.client.FileMetadataClient;
import com.wuxx.exchangeclear.file.dto.FileMetadataDTO;
import com.wuxx.exchangeclear.storage.ObjectStorageService;
import com.wuxx.exchangeclear.download.token.DownloadTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import javax.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileDownloadService {

    private final FileMetadataClient fileMetadataClient;

    private final ObjectStorageService objectStorageService;

    private final DownloadTokenService downloadTokenService;

    public void download(String fileNo, String token, String memberId, String clientIp, HttpServletResponse response) {
        FileMetadataDTO file = downloadTokenService.validateToken(fileNo, token, memberId, clientIp);
        response.setContentType("application/octet-stream");
        response.setHeader("Content-Length", String.valueOf(file.getFileSize()));
        response.setHeader("Content-Disposition", contentDisposition(file.getFileName()));

        try (InputStream inputStream = objectStorageService.download(file.getStoragePath())) {
            StreamUtils.copy(inputStream, response.getOutputStream());
            response.flushBuffer();
        } catch (Exception e) {
            throw new IllegalStateException("文件下载失败", e);
        }

        try {
            fileMetadataClient.increaseDownloadCount(fileNo);
        } catch (Exception e) {
            log.warn("文件已发送，但更新下载次数失败, fileNo={}", fileNo, e);
        }
    }

    private String contentDisposition(String fileName) {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + fileName + "\"; filename*=UTF-8''" + encoded;
    }
}
