package com.hajacheck.platformadmin.dto;

import com.hajacheck.admin.dto.AdminUserStatsResponse;
import java.util.List;

/**
 * stats는 AdminUserStatsResponse(#405)를 그대로 재사용한다 — 통계 카드 구조(총원/활성/정지/신규가입)는
 * 회사 스코프 유무와 무관하게 동일하다.
 */
public record PlatformAdminUserListResponse(
        List<PlatformAdminUserResponse> content,
        int page,
        int size,
        long totalElements,
        AdminUserStatsResponse stats) {
}
