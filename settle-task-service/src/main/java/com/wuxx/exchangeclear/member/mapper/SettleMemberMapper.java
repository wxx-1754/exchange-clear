package com.wuxx.exchangeclear.member.mapper;

import com.wuxx.exchangeclear.member.entity.SettleMember;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SettleMemberMapper {

    int deactivateAll();

    int upsert(SettleMember member);

    List<SettleMember> listActiveMembers();
}
