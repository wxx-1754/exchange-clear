package com.wuxx.exchangeclear.file.client;

import com.wuxx.exchangeclear.file.dto.FileMetadataDTO;
import com.wuxx.exchangeclear.file.dto.SaveGeneratedFileRequest;
import com.wuxx.exchangeclear.file.service.SettlementFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "exchange-clear.file.metadata-client", havingValue = "local", matchIfMissing = true)
public class LocalFileMetadataClient implements FileMetadataClient {

    private final SettlementFileService settlementFileService;

    @Override
    public FileMetadataDTO saveGeneratedFile(SaveGeneratedFileRequest request) {
        return settlementFileService.saveGeneratedFile(request);
    }

    @Override
    public FileMetadataDTO getMetadata(String fileNo) {
        return settlementFileService.metadata(fileNo);
    }

    @Override
    public FileMetadataDTO getMetadataByBiz(LocalDate settleDate, String memberId, String fileType, Integer version) {
        return settlementFileService.metadata(settleDate, memberId, fileType, version);
    }

    @Override
    public void increaseDownloadCount(String fileNo) {
        settlementFileService.increaseDownloadCount(fileNo);
    }
}
