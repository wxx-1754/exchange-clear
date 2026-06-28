package com.wuxx.exchangeclear.file.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReissueFileResponse {

    private String reissueNo;

    private String oldFileNo;

    private Integer newVersion;

    private String status;
}
