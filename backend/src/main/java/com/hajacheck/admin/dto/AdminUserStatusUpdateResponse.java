package com.hajacheck.admin.dto;

import com.hajacheck.auth.entity.UserStatus;

public record AdminUserStatusUpdateResponse(Long id, UserStatus status) {
}
