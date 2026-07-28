package com.hajacheck.platformadmin.dto;

import com.hajacheck.counsel.entity.CounselType;
import java.util.List;

/** 상담원 스킬 변경 모달이 여는 시점에 현재 배정된 스킬을 채우기 위한 조회 응답. */
public record PlatformAdminUserSkillsResponse(Long id, List<CounselType> skills) {
}
