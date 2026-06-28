package com.wuxx.exchangeclear.file.service;

import com.wuxx.exchangeclear.common.BizException;
import com.wuxx.exchangeclear.common.IdGenerator;
import com.wuxx.exchangeclear.enums.FileStatusEnum;
import com.wuxx.exchangeclear.file.dto.FileChecksumVO;
import com.wuxx.exchangeclear.file.dto.FileMetadataDTO;
import com.wuxx.exchangeclear.file.dto.FileVO;
import com.wuxx.exchangeclear.file.dto.SaveGeneratedFileRequest;
import com.wuxx.exchangeclear.file.entity.SettleFile;
import com.wuxx.exchangeclear.file.mapper.SettleFileMapper;
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

    @Transactional(rollbackFor = Exception.class)
    public FileMetadataDTO saveGeneratedFile(SaveGeneratedFileRequest request) {
        SettleFile file = new SettleFile();
        file.setFileNo(IdGenerator.next("FILE"));
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

        settleFileMapper.upsert(file);
        SettleFile saved = settleFileMapper.selectByBiz(
                request.getSettleDate(), request.getMemberId(), request.getFileType(), request.getVersion());
        if (saved == null) {
            throw new BizException("文件元数据保存失败：" + request.getTaskNo());
        }
        return toMetadataDTO(saved);
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

    public FileVO detail(String fileNo) {
        return toVO(getByFileNo(fileNo));
    }

    public SettleFile getByFileNo(String fileNo) {
        SettleFile file = settleFileMapper.selectByFileNo(fileNo);
        if (file == null) {
            throw new BizException("文件不存在：" + fileNo);
        }
        return file;
    }

    public FileMetadataDTO metadata(String fileNo) {
        return toMetadataDTO(getByFileNo(fileNo));
    }

    public FileMetadataDTO metadata(LocalDate settleDate, String memberId, String fileType, Integer version) {
        SettleFile file = settleFileMapper.selectByBiz(settleDate, memberId, fileType, version);
        if (file == null) {
            throw new BizException("文件不存在：" + settleDate + "/" + memberId + "/" + fileType + "/v" + version);
        }
        return toMetadataDTO(file);
    }

    public void increaseDownloadCount(String fileNo) {
        int affected = settleFileMapper.increaseDownloadCount(fileNo);
        if (affected == 0) {
            throw new BizException("文件不存在：" + fileNo);
        }
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

    public FileMetadataDTO toMetadataDTO(SettleFile file) {
        FileMetadataDTO dto = new FileMetadataDTO();
        dto.setFileNo(file.getFileNo());
        dto.setTaskNo(file.getTaskNo());
        dto.setSettleDate(file.getSettleDate());
        dto.setMemberId(file.getMemberId());
        dto.setFileType(file.getFileType());
        dto.setFileName(file.getFileName());
        dto.setFileSize(file.getFileSize());
        dto.setFileMd5(file.getFileMd5());
        dto.setStorageBucket(file.getStorageBucket());
        dto.setStoragePath(file.getStoragePath());
        dto.setVersion(file.getVersion());
        dto.setStatus(file.getStatus());
        dto.setDownloadCount(file.getDownloadCount());
        return dto;
    }
}
