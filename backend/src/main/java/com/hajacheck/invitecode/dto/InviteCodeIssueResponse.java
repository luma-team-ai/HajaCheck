package com.hajacheck.invitecode.dto;

/** 초대 코드 발급 응답(#794). ttlSeconds는 프론트 카운트다운 초기값으로 그대로 쓰인다. */
public record InviteCodeIssueResponse(
        String code,
        long ttlSeconds
) {
}
