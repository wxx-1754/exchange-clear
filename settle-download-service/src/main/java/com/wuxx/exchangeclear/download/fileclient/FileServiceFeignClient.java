package com.wuxx.exchangeclear.download.fileclient;

import com.wuxx.exchangeclear.common.Result;
import com.wuxx.exchangeclear.file.dto.FileMetadataDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;

@FeignClient(name = "settle-file-service", contextId = "downloadFileServiceFeignClient")
public interface FileServiceFeignClient {

    @GetMapping("/internal/files/{fileNo}")
    Result<FileMetadataDTO> metadata(@PathVariable("fileNo") String fileNo);

    @GetMapping("/internal/files/metadata")
    Result<FileMetadataDTO> metadata(@RequestParam("settleDate") LocalDate settleDate,
                                     @RequestParam("memberId") String memberId,
                                     @RequestParam("fileType") String fileType,
                                     @RequestParam("version") Integer version);

    @PostMapping("/internal/files/{fileNo}/download-count/increase")
    Result<Void> increaseDownloadCount(@PathVariable("fileNo") String fileNo);
}
