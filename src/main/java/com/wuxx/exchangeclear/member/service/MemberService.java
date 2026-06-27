package com.wuxx.exchangeclear.member.service;

import com.wuxx.exchangeclear.member.entity.SettleMember;
import com.wuxx.exchangeclear.member.mapper.SettleMemberMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final SettleMemberMapper settleMemberMapper;

    public List<SettleMember> listActiveMembers() {
        return settleMemberMapper.listActiveMembers();
    }
}
