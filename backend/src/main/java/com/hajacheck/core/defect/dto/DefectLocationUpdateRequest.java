package com.hajacheck.core.defect.dto;

import jakarta.validation.constraints.Size;

/**
 * 하자 위치 사후 편집 요청(#970 갭3) — "조치 등록 시 입력"이 아니라 검수자가 언제든 편집 가능한
 * 가벼운 필드라 별도 엔드포인트(PATCH /api/defects/{id}/location)로 분리했다. location은
 * nullable을 허용한다(위치를 지우는 편집도 정상 케이스) — 빈 문자열/공백은
 * {@link com.hajacheck.core.defect.entity.Defect#updateLocation(String)}이 null로 정규화한다.
 */
public record DefectLocationUpdateRequest(
        @Size(max = 300) String location
) {
}
