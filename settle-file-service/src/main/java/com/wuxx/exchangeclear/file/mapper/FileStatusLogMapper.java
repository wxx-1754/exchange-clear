package com.wuxx.exchangeclear.file.mapper;

import com.wuxx.exchangeclear.file.entity.FileStatusLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface FileStatusLogMapper {

    int insert(FileStatusLog statusLog);

    List<FileStatusLog> listByFileNo(@Param("fileNo") String fileNo);
}
