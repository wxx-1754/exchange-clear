package com.wuxx.exchangeclear.worker.fileclient;

import com.wuxx.exchangeclear.common.Result;
import com.wuxx.exchangeclear.file.dto.FileMetadataDTO;
import com.wuxx.exchangeclear.file.dto.SaveGeneratedFileRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import javax.validation.Valid;
import java.time.LocalDate;

@FeignClient(name = "settle-file-service", contextId = "workerFileServiceFeignClient")
public interface FileServiceFeignClient {

    @PostMapping("/internal/files/generated")
    Result<FileMetadataDTO> saveGeneratedFile(@Valid @RequestBody SaveGeneratedFileRequest request);

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
