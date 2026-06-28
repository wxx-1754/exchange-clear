package com.wuxx.exchangeclear.mock.service;

import com.wuxx.exchangeclear.config.MockProperties;
import com.wuxx.exchangeclear.member.entity.SettleMember;
import com.wuxx.exchangeclear.member.mapper.SettleMemberMapper;
import com.wuxx.exchangeclear.mock.dto.MockInitRequest;
import com.wuxx.exchangeclear.mock.dto.MockInitResponse;
import com.wuxx.exchangeclear.trade.entity.TradeRecord;
import com.wuxx.exchangeclear.trade.mapper.TradeRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class MockDataService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final String[] PRODUCT_IDS = {"IF", "IC", "IH", "IM"};

    private static final String[] DIRECTIONS = {"BUY", "SELL"};

    private final SettleMemberMapper settleMemberMapper;

    private final TradeRecordMapper tradeRecordMapper;

    private final MockProperties mockProperties;

    @Transactional(rollbackFor = Exception.class)
    public MockInitResponse init(MockInitRequest request) {
        log.info("开始初始化模拟数据, settleDate={}, memberCount={}, tradeCountPerMember={}",
                request.getSettleDate(), request.getMemberCount(), request.getTradeCountPerMember());

        tradeRecordMapper.deleteBySettleDate(request.getSettleDate());
        settleMemberMapper.deactivateAll();
        for (int i = 1; i <= request.getMemberCount(); i++) {
            settleMemberMapper.upsert(buildMember(i));
        }

        long totalTradeCount = generateTrades(request);
        log.info("模拟数据初始化完成, settleDate={}, memberCount={}, tradeCount={}",
                request.getSettleDate(), request.getMemberCount(), totalTradeCount);
        return new MockInitResponse(request.getSettleDate(), request.getMemberCount(), totalTradeCount);
    }

    private SettleMember buildMember(int index) {
        String memberId = String.format("%04d", index);
        SettleMember member = new SettleMember();
        member.setMemberId(memberId);
        member.setMemberName("会员" + memberId);
        member.setStatus("ACTIVE");
        return member;
    }

    private long generateTrades(MockInitRequest request) {
        int batchSize = mockProperties.getBatchSize();
        List<TradeRecord> batch = new ArrayList<>(batchSize);
        long sequence = 1L;
        for (int memberIndex = 1; memberIndex <= request.getMemberCount(); memberIndex++) {
            String memberId = String.format("%04d", memberIndex);
            for (int tradeIndex = 0; tradeIndex < request.getTradeCountPerMember(); tradeIndex++) {
                batch.add(buildTradeRecord(request, memberId, sequence));
                sequence++;
                if (batch.size() >= batchSize) {
                    tradeRecordMapper.batchInsert(batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            tradeRecordMapper.batchInsert(batch);
        }
        return (long) request.getMemberCount() * request.getTradeCountPerMember();
    }

    private TradeRecord buildTradeRecord(MockInitRequest request, String memberId, long sequence) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String productId = PRODUCT_IDS[random.nextInt(PRODUCT_IDS.length)];
        BigDecimal price = BigDecimal.valueOf(random.nextDouble(3000, 6000))
                .setScale(4, RoundingMode.HALF_UP);
        int volume = random.nextInt(1, 11);

        TradeRecord record = new TradeRecord();
        record.setSettleDate(request.getSettleDate());
        record.setMemberId(memberId);
        record.setTradeNo("T" + request.getSettleDate().format(DATE_FORMATTER) + String.format("%012d", sequence));
        record.setProductId(productId);
        record.setContractId(productId + "2606");
        record.setDirection(DIRECTIONS[random.nextInt(DIRECTIONS.length)]);
        record.setPrice(price);
        record.setVolume(volume);
        record.setAmount(price.multiply(BigDecimal.valueOf(volume)).setScale(4, RoundingMode.HALF_UP));
        record.setTradeTime(LocalDateTime.of(request.getSettleDate(), randomTradeTime()));
        return record;
    }

    private LocalTime randomTradeTime() {
        int startSecond = 9 * 3600 + 30 * 60;
        int endSecond = 15 * 3600;
        return LocalTime.ofSecondOfDay(ThreadLocalRandom.current().nextInt(startSecond, endSecond));
    }
}
