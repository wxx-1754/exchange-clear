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

    List<SettleFile> listPublishCandidates(@Param("settleDate") LocalDate settleDate,
                                           @Param("fileType") String fileType,
                                           @Param("version") Integer version);

    List<SettleFile> listRecentFiles(@Param("days") int days);

    List<SettleFile> listInvalidPublishedFiles();

    List<SettleFile> listReissuedMissingNewVersion();

    int updateToPublished(@Param("fileNo") String fileNo,
                          @Param("reason") String reason);

    int updateToRevoked(@Param("fileNo") String fileNo,
                        @Param("reason") String reason);

    int updateToReissued(@Param("fileNo") String fileNo,
                         @Param("reason") String reason);

    int increaseDownloadCount(@Param("fileNo") String fileNo);
}
