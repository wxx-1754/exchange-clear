package com.wuxx.exchangeclear.file.mapper;

import com.wuxx.exchangeclear.file.entity.SettleFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface SettleFileMapper {

    int upsert(SettleFile file);

    SettleFile selectByFileNo(@Param("fileNo") String fileNo);

    SettleFile selectByBiz(@Param("settleDate") LocalDate settleDate,
                           @Param("memberId") String memberId,
                           @Param("fileType") String fileType,
                           @Param("version") Integer version);

    List<SettleFile> list(@Param("settleDate") LocalDate settleDate,
                          @Param("memberId") String memberId,
                          @Param("fileType") String fileType);

    int increaseDownloadCount(@Param("fileNo") String fileNo);
}
