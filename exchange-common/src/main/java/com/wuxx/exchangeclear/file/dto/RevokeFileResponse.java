package com.wuxx.exchangeclear.file.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RevokeFileResponse {

    private String fileNo;

    private String status;
}
