package com.hajacheck.core.defect.dto;

import com.hajacheck.core.defect.entity.DefectStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * 조치 결과 등록(HAJA-393/#725, "상태 저장" 버튼) — 조치 후 사진/조치 내용/조치일/담당자
 * 4개 필드는 Figma 실사(node 1562-3682)로 확정된 필수 항목이다.
 *
 * <p>targetStatus(#1128)는 같은 폼으로 조치중(IN_PROGRESS)·조치완료(RESOLVED)를 모두 저장하기 위해
 * 추가된 전이 목표 상태다. 이전에는 항상 RESOLVED로 고정 전이했으나, 폼에 "진행상태" select가
 * 생기면서 사용자가 다음 단계를 직접 고른다. 허용 값은 IN_PROGRESS/RESOLVED 두 개뿐이며
 * (DETECTED/CONFIRMED는 조치 등록의 타겟이 될 수 없음) 이 제약은 서비스 계층
 * ({@link com.hajacheck.core.defect.service.DefectService#registerActionResult})이 도메인 검증으로
 * 거부한다. 실제 전이 가능 여부(정방향 한 단계인지)는 기존
 * {@link com.hajacheck.core.defect.entity.Defect#changeStatus} 규칙이 그대로 판정한다.
 */
public record DefectActionResultRequest(
        @NotNull Long actionMediaId,
        @NotBlank @Size(max = 2000) String actionContent,
        @NotNull LocalDate actionDate,
        @NotNull Long actionAssigneeId,
        @NotNull DefectStatus targetStatus
) {
}
