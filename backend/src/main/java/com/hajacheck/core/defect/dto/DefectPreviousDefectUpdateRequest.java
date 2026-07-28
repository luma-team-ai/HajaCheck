package com.hajacheck.core.defect.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 회차 간 대응 하자 확정 요청(HAJA-437) — 자동 후보 제안 알고리즘은 이번 스코프 밖이라, 검수자가
 * 화면에서 이미 알고 있는 이전 회차 하자 id를 직접 입력해 확정하는 것까지만 다룬다.
 * previousDefectId가 가리키는 하자가 (a) 같은 회사 스코프 (b) 같은 시설물 (c) 더 이전 회차인지는
 * {@link com.hajacheck.core.defect.service.DefectService#confirmPreviousDefect}가 검증한다.
 */
public record DefectPreviousDefectUpdateRequest(
        @NotNull Long previousDefectId
) {
}
