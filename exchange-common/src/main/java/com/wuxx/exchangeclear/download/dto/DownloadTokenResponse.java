package com.wuxx.exchangeclear.download.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DownloadTokenResponse {

    private String token;

    private Long expireSeconds;
}
