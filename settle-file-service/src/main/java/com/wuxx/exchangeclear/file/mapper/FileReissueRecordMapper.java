package com.wuxx.exchangeclear.file.mapper;

import com.wuxx.exchangeclear.file.entity.FileReissueRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FileReissueRecordMapper {

    int insert(FileReissueRecord record);
}
