package com.wuxx.exchangeclear.trade.mapper;

import com.wuxx.exchangeclear.trade.entity.TradeRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * mock-service 负责生成模拟成交数据，只写入不读取。
 */
@Mapper
public interface TradeRecordMapper {

    int deleteBySettleDate(@Param("settleDate") LocalDate settleDate);

    int batchInsert(@Param("records") List<TradeRecord> records);
}
