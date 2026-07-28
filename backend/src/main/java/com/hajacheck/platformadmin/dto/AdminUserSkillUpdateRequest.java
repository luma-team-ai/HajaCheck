package com.hajacheck.platformadmin.dto;

import com.hajacheck.counsel.entity.CounselType;
import jakarta.validation.constraints.NotNull;

public record AdminUserSkillUpdateRequest(@NotNull CounselType skill) {
}
