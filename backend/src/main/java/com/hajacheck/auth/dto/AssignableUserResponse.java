package com.hajacheck.auth.dto;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;

/**
 * 배정 가능한 담당자 후보 응답 DTO(#690 PR머신 P3) — {@link UserResponse}는 email 을 포함해
 * 배정 피커처럼 "같은 회사 구성원이면 누구나 조회 가능"한 목록 응답에는 개인정보 노출 표면이
 * 넓다. 피커 UI 식별에는 id/name/role 로 충분해 email 을 제외한 경량 DTO로 별도 정의한다.
 */
public record AssignableUserResponse(
        Long id,
        String name,
        Role role
) {
    public static AssignableUserResponse from(User user) {
        return new AssignableUserResponse(user.getId(), user.getName(), user.getRole());
    }
}
