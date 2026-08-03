package com.hajacheck.core.defect.service;

import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 하자 쓰기(생성·검수·조치등록·위치수정·회차대응확정·상태전이) 공통 회차 상태 가드(PR머신 리뷰
 * 4차 P1) — 소속 회차가 ANALYZING·FAILED면 모든 하자 쓰기 진입점을 거부한다.
 *
 * <p><b>ANALYZING</b>: 워커가 첫 탐지 성공 시 호출하는 {@link DefectWriter
 * #softDeleteAllForInspectionThenSave}가 이 회차의 비삭제 하자 전체를 대체하므로, 분석이 도는 동안
 * 사람이 하자를 만들거나 손대면 그 결과가 지워진다(코드 리뷰 P1, 머신 검수 2차 — 원래 createManualDefect
 * 에만 걸려있었으나 reviewDefect/DefectService의 나머지 쓰기 경로엔 이 가드가 없어 같은 유실이 그
 * 형제 경로로 열려 있었다 — 4차 리뷰에서 지적, 이 PR 이전부터의 표면).
 *
 * <p><b>FAILED</b>: FAILED 재분석의 원자적 선점({@link com.hajacheck.core.inspection.repository
 * .InspectionRepository#startAnalyzingIfNotRunning})은 "비삭제 하자 없음" 요건을 FAILED에 한해
 * 건너뛰는데, 그 전제("FAILED에 남은 하자는 전부 이번에 실패한 실행이 만든 AI 결과뿐")가 성립하려면
 * FAILED 회차에서 하자를 만들거나 손대는 모든 경로를 막아야 한다. createManualDefect만 막았을 때는
 * (3차 리뷰 P1) reviewDefect(등급 조정·오탐 복구)와 DefectService의 registerActionResult/
 * updateLocation/confirmPreviousDefect/updateStatus가 여전히 뚫려 있어, 사람이 FAILED 회차 하자를
 * 큐레이션한 뒤 재분석하면 워커의 전체 소프트삭제가 그 큐레이션을 무보상으로 지웠다(4차 리뷰 P1).
 */
@Component
public class DefectInspectionWriteGuard {

    public void requireWritable(InspectionStatus status) {
        if (status == InspectionStatus.ANALYZING) {
            throw new BusinessException(ErrorCode.ANALYSIS_ALREADY_RUNNING);
        }
        if (status == InspectionStatus.FAILED) {
            throw new BusinessException(ErrorCode.DEFECT_WRITE_BLOCKED_ANALYSIS_FAILED);
        }
    }
}
