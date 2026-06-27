package com.wuxx.exchangeclear.member.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SettleMember {

    private Long id;

    private String memberId;

    private String memberName;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
