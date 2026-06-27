package com.wuxx.exchangeclear.file.service;

import com.wuxx.exchangeclear.common.BizException;
import com.wuxx.exchangeclear.common.IdGenerator;
import com.wuxx.exchangeclear.enums.FileStatusEnum;
import com.wuxx.exchangeclear.file.dto.FileChecksumVO;
import com.wuxx.exchangeclear.file.dto.FileVO;
import com.wuxx.exchangeclear.file.entity.SettleFile;
import com.wuxx.exchangeclear.file.mapper.SettleFileMapper;
import com.wuxx.exchangeclear.generator.FileGenerateResult;
import com.wuxx.exchangeclear.storage.StorageUploadResult;
import com.wuxx.exchangeclear.task.entity.SettleFileTask;
import com.wuxx.exchangeclear.task.mapper.SettleFileTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SettlementFileService {

    private final SettleFileMapper settleFileMapper;

    private final SettleFileTaskMapper settleFileTaskMapper;

    @Transactional(rollbackFor = Exception.class)
    public SettleFile saveGeneratedFile(SettleFileTask task,
                                        FileGenerateResult generateResult,
                                        StorageUploadResult storageResult) {
        SettleFile file = new SettleFile();
        file.setFileNo(IdGenerator.next("FILE"));
        file.setTaskNo(task.getTaskNo());
        file.setSettleDate(task.getSettleDate());
        file.setMemberId(task.getMemberId());
        file.setFileType(task.getFileType());
        file.setFileName(generateResult.getFileName());
        file.setFileSize(generateResult.getFileSize());
        file.setFileMd5(generateResult.getFileMd5());
        file.setStorageBucket(storageResult.getBucket());
        file.setStoragePath(storageResult.getObjectName());
        file.setVersion(task.getVersion());
        file.setStatus(FileStatusEnum.GENERATED.getCode());

        settleFileMapper.upsert(file);
        settleFileTaskMapper.updateGenerated(task.getTaskNo());
        return settleFileMapper.selectByBiz(task.getSettleDate(), task.getMemberId(), task.getFileType(), task.getVersion());
    }

    public List<FileVO> list(LocalDate settleDate, String memberId, String fileType) {
        return settleFileMapper.list(settleDate, memberId, fileType)
                .stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    public FileChecksumVO checksum(String fileNo) {
        SettleFile file = getByFileNo(fileNo);
        return new FileChecksumVO(file.getFileNo(), file.getFileName(), file.getFileSize(), file.getFileMd5());
    }

    public SettleFile getByFileNo(String fileNo) {
        SettleFile file = settleFileMapper.selectByFileNo(fileNo);
        if (file == null) {
            throw new BizException("文件不存在：" + fileNo);
        }
        return file;
    }

    public FileVO toVO(SettleFile file) {
        FileVO vo = new FileVO();
        vo.setFileNo(file.getFileNo());
        vo.setSettleDate(file.getSettleDate());
        vo.setMemberId(file.getMemberId());
        vo.setFileType(file.getFileType());
        vo.setFileName(file.getFileName());
        vo.setFileSize(file.getFileSize());
        vo.setFileMd5(file.getFileMd5());
        vo.setStatus(file.getStatus());
        vo.setVersion(file.getVersion());
        vo.setDownloadCount(file.getDownloadCount());
        return vo;
    }
}
