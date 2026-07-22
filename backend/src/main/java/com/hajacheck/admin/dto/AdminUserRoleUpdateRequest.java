package com.hajacheck.admin.dto;

import com.hajacheck.auth.entity.Role;
import jakarta.validation.constraints.NotNull;

public record AdminUserRoleUpdateRequest(@NotNull Role role) {
}
