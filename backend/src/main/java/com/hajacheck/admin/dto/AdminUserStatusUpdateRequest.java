package com.hajacheck.admin.dto;

import com.hajacheck.auth.entity.UserStatus;
import jakarta.validation.constraints.NotNull;

public record AdminUserStatusUpdateRequest(@NotNull UserStatus status) {
}
