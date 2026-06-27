package com.wuxx.exchangeclear.mock.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import java.time.LocalDate;

@Data
public class MockInitRequest {

    @NotNull(message = "结算日期不能为空")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate settleDate;

    @NotNull(message = "会员数量不能为空")
    @Min(value = 1, message = "会员数量不能小于1")
    @Max(value = 1000, message = "会员数量不能大于1000")
    private Integer memberCount;

    @NotNull(message = "每会员成交数量不能为空")
    @Min(value = 1, message = "每会员成交数量不能小于1")
    @Max(value = 1000000, message = "每会员成交数量不能大于1000000")
    private Integer tradeCountPerMember;
}
