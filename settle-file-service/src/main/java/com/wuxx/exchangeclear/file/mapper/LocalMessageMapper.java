package com.wuxx.exchangeclear.file.mapper;

import com.wuxx.exchangeclear.file.entity.LocalMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface LocalMessageMapper {

    int insert(LocalMessage message);

    LocalMessage selectByMessageId(@Param("messageId") String messageId);

    List<LocalMessage> selectRetryMessages(@Param("now") LocalDateTime now,
                                           @Param("limit") int limit);

    int markSent(@Param("messageId") String messageId);

    int markFailed(@Param("messageId") String messageId,
                   @Param("errorMessage") String errorMessage,
                   @Param("nextRetryTime") LocalDateTime nextRetryTime);
}
