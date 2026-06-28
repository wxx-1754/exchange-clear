package com.wuxx.exchangeclear.trade.mapper;

import com.wuxx.exchangeclear.trade.entity.TradeRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * worker-service 只读取成交数据用于生成结算文件，不负责写入。
 */
@Mapper
public interface TradeRecordMapper {

    List<TradeRecord> selectNextPage(@Param("settleDate") LocalDate settleDate,
                                     @Param("memberId") String memberId,
                                     @Param("lastId") Long lastId,
                                     @Param("limit") Integer limit);
}
