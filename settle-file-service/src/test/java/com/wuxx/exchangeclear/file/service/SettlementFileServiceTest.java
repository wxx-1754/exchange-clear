package com.wuxx.exchangeclear.file.service;

import com.wuxx.exchangeclear.common.BizException;
import com.wuxx.exchangeclear.enums.FileStatusEnum;
import com.wuxx.exchangeclear.file.dto.FileMetadataDTO;
import com.wuxx.exchangeclear.file.dto.SaveGeneratedFileRequest;
import com.wuxx.exchangeclear.file.entity.SettleFile;
import com.wuxx.exchangeclear.file.mapper.SettleFileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementFileServiceTest {

    @Mock
    private SettleFileMapper settleFileMapper;

    private SettlementFileService settlementFileService;

    @BeforeEach
    void setUp() {
        settlementFileService = new SettlementFileService(settleFileMapper);
    }

    @Test
    void saveGeneratedFileShouldUpsertMetadataFromRequest() {
        SaveGeneratedFileRequest request = request();
        SettleFile savedFile = file(request);
        when(settleFileMapper.selectByBiz(
                request.getSettleDate(), request.getMemberId(), request.getFileType(), request.getVersion()))
                .thenReturn(savedFile);

        FileMetadataDTO metadata = settlementFileService.saveGeneratedFile(request);

        ArgumentCaptor<SettleFile> captor = ArgumentCaptor.forClass(SettleFile.class);
        verify(settleFileMapper).upsert(captor.capture());
        assertEquals(FileStatusEnum.GENERATED.getCode(), captor.getValue().getStatus());
        assertEquals(request.getTaskNo(), captor.getValue().getTaskNo());
        assertEquals(savedFile.getFileNo(), metadata.getFileNo());
        assertEquals(savedFile.getStoragePath(), metadata.getStoragePath());
    }

    @Test
    void increaseDownloadCountShouldFailWhenFileNotExists() {
        when(settleFileMapper.increaseDownloadCount("FILE404")).thenReturn(0);

        assertThrows(BizException.class, () -> settlementFileService.increaseDownloadCount("FILE404"));
    }

    private SaveGeneratedFileRequest request() {
        SaveGeneratedFileRequest request = new SaveGeneratedFileRequest();
        request.setTaskNo("TASK001");
        request.setSettleDate(LocalDate.of(2026, 6, 26));
        request.setMemberId("0001");
        request.setFileType("TRADE");
        request.setFileName("trade_0001_20260626.csv");
        request.setFileSize(10L);
        request.setFileMd5("md5");
        request.setStorageBucket("exchange-clear");
        request.setStoragePath("20260626/0001/v1/TRADE/trade_0001_20260626.csv");
        request.setVersion(1);
        return request;
    }

    private SettleFile file(SaveGeneratedFileRequest request) {
        SettleFile file = new SettleFile();
        file.setFileNo("FILE001");
        file.setTaskNo(request.getTaskNo());
        file.setSettleDate(request.getSettleDate());
        file.setMemberId(request.getMemberId());
        file.setFileType(request.getFileType());
        file.setFileName(request.getFileName());
        file.setFileSize(request.getFileSize());
        file.setFileMd5(request.getFileMd5());
        file.setStorageBucket(request.getStorageBucket());
        file.setStoragePath(request.getStoragePath());
        file.setVersion(request.getVersion());
        file.setStatus(FileStatusEnum.GENERATED.getCode());
        file.setDownloadCount(0L);
        return file;
    }
}
