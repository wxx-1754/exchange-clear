package com.wuxx.exchangeclear.trade.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class TradeRecord {

    private Long id;

    private LocalDate settleDate;

    private String memberId;

    private String tradeNo;

    private String productId;

    private String contractId;

    private String direction;

    private BigDecimal price;

    private Integer volume;

    private BigDecimal amount;

    private LocalDateTime tradeTime;

    private LocalDateTime createdAt;
}
