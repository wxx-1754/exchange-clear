package com.wuxx.exchangeclear.file.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PublishFileResponse {

    private String batchNo;

    private LocalDate settleDate;

    private Integer publishCount;

    private String status;
}
