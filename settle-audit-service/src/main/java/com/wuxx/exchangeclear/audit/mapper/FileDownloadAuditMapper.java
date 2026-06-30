package com.wuxx.exchangeclear.audit.mapper;

import com.wuxx.exchangeclear.audit.dto.DownloadAuditQuery;
import com.wuxx.exchangeclear.audit.dto.DownloadRankDTO;
import com.wuxx.exchangeclear.audit.dto.FileDownloadStatsDTO;
import com.wuxx.exchangeclear.audit.dto.MemberDownloadStatsDTO;
import com.wuxx.exchangeclear.audit.entity.FileDownloadAudit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FileDownloadAuditMapper {

    int insert(FileDownloadAudit audit);

    FileDownloadAudit selectByAuditNo(@Param("auditNo") String auditNo);

    long countByQuery(@Param("query") DownloadAuditQuery query);

    List<FileDownloadAudit> listByQuery(@Param("query") DownloadAuditQuery query,
                                        @Param("offset") int offset,
                                        @Param("pageSize") int pageSize);

    List<FileDownloadStatsDTO> statFileDownloads(@Param("settleDate") LocalDate settleDate,
                                                 @Param("fileType") String fileType,
                                                 @Param("fileNo") String fileNo);

    List<MemberDownloadStatsDTO> statMemberDownloads(@Param("memberId") String memberId,
                                                     @Param("startTime") LocalDateTime startTime,
                                                     @Param("endTime") LocalDateTime endTime);

    long countByStatus(@Param("status") String status,
                       @Param("startTime") LocalDateTime startTime,
                       @Param("endTime") LocalDateTime endTime);

    List<DownloadRankDTO> topIps(@Param("startTime") LocalDateTime startTime,
                                 @Param("endTime") LocalDateTime endTime,
                                 @Param("limit") int limit);

    List<DownloadRankDTO> topMembers(@Param("startTime") LocalDateTime startTime,
                                     @Param("endTime") LocalDateTime endTime,
                                     @Param("limit") int limit);
}
