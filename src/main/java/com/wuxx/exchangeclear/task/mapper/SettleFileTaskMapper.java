package com.wuxx.exchangeclear.task.mapper;

import com.wuxx.exchangeclear.task.entity.SettleFileTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface SettleFileTaskMapper {

    int insertIgnore(SettleFileTask task);

    SettleFileTask selectByTaskNo(@Param("taskNo") String taskNo);

    List<SettleFileTask> list(@Param("settleDate") LocalDate settleDate,
                              @Param("status") String status);

    List<SettleFileTask> selectPendingTasks(@Param("settleDate") LocalDate settleDate,
                                            @Param("fileType") String fileType);

    List<SettleFileTask> selectTasksForSend(@Param("settleDate") LocalDate settleDate,
                                            @Param("fileType") String fileType,
                                            @Param("status") String status);

    int updateStatusToGenerating(@Param("taskNo") String taskNo);

    int updateGenerated(@Param("taskNo") String taskNo);

    int updateFailed(@Param("taskNo") String taskNo,
                     @Param("errorMessage") String errorMessage);

    int updateSent(@Param("taskNo") String taskNo,
                   @Param("messageId") String messageId);

    int updateSendFailed(@Param("taskNo") String taskNo,
                         @Param("errorMessage") String errorMessage);
}
