package com.wuxx.exchangeclear.download.service;

import com.wuxx.exchangeclear.file.client.FileMetadataClient;
import com.wuxx.exchangeclear.file.dto.FileMetadataDTO;
import com.wuxx.exchangeclear.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import javax.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class FileDownloadService {

    private final FileMetadataClient fileMetadataClient;

    private final ObjectStorageService objectStorageService;

    public void download(String fileNo, HttpServletResponse response) {
        FileMetadataDTO file = fileMetadataClient.getMetadata(fileNo);
        response.setContentType("application/octet-stream");
        response.setHeader("Content-Length", String.valueOf(file.getFileSize()));
        response.setHeader("Content-Disposition", contentDisposition(file.getFileName()));

        try (InputStream inputStream = objectStorageService.download(file.getStoragePath())) {
            StreamUtils.copy(inputStream, response.getOutputStream());
            response.flushBuffer();
            fileMetadataClient.increaseDownloadCount(fileNo);
        } catch (Exception e) {
            throw new IllegalStateException("文件下载失败", e);
        }
    }

    private String contentDisposition(String fileName) {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + fileName + "\"; filename*=UTF-8''" + encoded;
    }
}
