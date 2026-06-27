package com.wuxx.exchangeclear.trade.mapper;

import com.wuxx.exchangeclear.trade.entity.TradeRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface TradeRecordMapper {

    int deleteBySettleDate(@Param("settleDate") LocalDate settleDate);

    int batchInsert(@Param("records") List<TradeRecord> records);

    List<TradeRecord> selectNextPage(@Param("settleDate") LocalDate settleDate,
                                     @Param("memberId") String memberId,
                                     @Param("lastId") Long lastId,
                                     @Param("limit") Integer limit);
}
