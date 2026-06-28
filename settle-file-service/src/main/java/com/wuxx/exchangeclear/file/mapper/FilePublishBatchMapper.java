package com.wuxx.exchangeclear.file.mapper;

import com.wuxx.exchangeclear.file.entity.FilePublishBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface FilePublishBatchMapper {

    int insert(FilePublishBatch batch);

    int updateStatus(@Param("batchNo") String batchNo,
                     @Param("status") String status,
                     @Param("totalCount") Integer totalCount,
                     @Param("successCount") Integer successCount,
                     @Param("failedCount") Integer failedCount,
                     @Param("errorMessage") String errorMessage);

    List<FilePublishBatch> list(@Param("settleDate") LocalDate settleDate);
}
