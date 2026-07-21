package com.hajacheck.admin.dto;

import java.util.List;

public record AdminUserListResponse(
        List<AdminUserResponse> content,
        int page,
        int size,
        long totalElements,
        AdminUserStatsResponse stats) {
}
