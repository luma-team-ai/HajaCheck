package com.hajacheck.admin.dto;

import com.hajacheck.auth.entity.Role;

public record AdminUserRoleUpdateResponse(Long id, Role role) {
}
