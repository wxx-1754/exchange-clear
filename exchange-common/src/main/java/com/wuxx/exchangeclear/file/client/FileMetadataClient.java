package com.wuxx.exchangeclear.file.client;

import com.wuxx.exchangeclear.file.dto.FileMetadataDTO;
import com.wuxx.exchangeclear.file.dto.SaveGeneratedFileRequest;

import java.time.LocalDate;

public interface FileMetadataClient {

    FileMetadataDTO saveGeneratedFile(SaveGeneratedFileRequest request);

    FileMetadataDTO getMetadata(String fileNo);

    FileMetadataDTO getMetadataByBiz(LocalDate settleDate, String memberId, String fileType, Integer version);

    void increaseDownloadCount(String fileNo);
}
