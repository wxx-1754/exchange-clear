package com.wuxx.exchangeclear.download.fileclient;

import com.wuxx.exchangeclear.common.BizException;
import com.wuxx.exchangeclear.common.Result;
import com.wuxx.exchangeclear.file.client.FileMetadataClient;
import com.wuxx.exchangeclear.file.dto.FileMetadataDTO;
import com.wuxx.exchangeclear.file.dto.SaveGeneratedFileRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "exchange-clear.file.metadata-client", havingValue = "feign", matchIfMissing = true)
public class FeignFileMetadataClient implements FileMetadataClient {

    private final FileServiceFeignClient fileServiceFeignClient;

    @Override
    public FileMetadataDTO saveGeneratedFile(SaveGeneratedFileRequest request) {
        throw new UnsupportedOperationException("download-service不支持保存文件元数据");
    }

    @Override
    public FileMetadataDTO getMetadata(String fileNo) {
        return unwrap(fileServiceFeignClient.metadata(fileNo), "查询文件元数据失败：" + fileNo);
    }

    @Override
    public FileMetadataDTO getMetadataByBiz(LocalDate settleDate, String memberId, String fileType, Integer version) {
        return unwrap(fileServiceFeignClient.metadata(settleDate, memberId, fileType, version),
                "查询文件元数据失败：" + settleDate + "/" + memberId + "/" + fileType + "/v" + version);
    }

    @Override
    public void increaseDownloadCount(String fileNo) {
        unwrap(fileServiceFeignClient.increaseDownloadCount(fileNo), "更新下载次数失败：" + fileNo);
    }

    private <T> T unwrap(Result<T> result, String fallbackMessage) {
        if (result == null) {
            throw new BizException(fallbackMessage);
        }
        if (result.getCode() == null || result.getCode() != 0) {
            throw new BizException(result.getMessage() == null ? fallbackMessage : result.getMessage());
        }
        return result.getData();
    }
}
