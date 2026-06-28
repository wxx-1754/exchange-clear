package com.wuxx.exchangeclear.mock.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDate;

@Data
@AllArgsConstructor
public class MockInitResponse {

    private LocalDate settleDate;

    private Integer memberCount;

    private Long tradeCount;
}
