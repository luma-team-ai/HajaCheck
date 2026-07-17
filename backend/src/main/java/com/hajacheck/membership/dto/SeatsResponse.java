package com.hajacheck.membership.dto;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import java.util.List;

/**
 * GET /api/me/seats 응답 — 계약(contract.md "마이페이지" v1) 그대로.
 * 이메일은 회사 내부 팀 명부이므로 비마스킹(동일 회사 소속만 조회 가능).
 */
public record SeatsResponse(int used, Integer limit, List<Member> members) {

    public record Member(Long userId, String name, String email, Role role, UserStatus status) {

        public static Member from(User user) {
            return new Member(user.getId(), user.getName(), user.getEmail(), user.getRole(), user.getStatus());
        }
    }

    public static SeatsResponse of(int used, List<User> members, Integer limit) {
        return new SeatsResponse(
                used,
                limit,
                members.stream().map(Member::from).toList());
    }
}
