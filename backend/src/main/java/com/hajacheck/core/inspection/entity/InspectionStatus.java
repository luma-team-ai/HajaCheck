package com.hajacheck.core.inspection.entity;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 점검 처리 상태 — DDL inspection_status_type(생성/업로드중/분석중/분석완료/검토완료/보고서화).
 *
 * <p><b>허용 전이 테이블(상태 머신 중앙화, 코드 리뷰 종결)</b> — 지금까지 상태 전이 규칙이
 * {@code advanceTo}(검증 없는 setter)와 여러 개별 가드에 흩어져 있어, 인접 시나리오마다 새 구멍이
 * 반복 발견됐다. 허용 전이를 이 한 곳에 명시하고 {@link com.hajacheck.core.inspection.entity.Inspection#advanceTo}가
 * 이 테이블로만 전이를 허용하게 해, "허용되지 않은 전이는 항상 거부"를 불변식으로 고정한다.
 */
public enum InspectionStatus {
    CREATED,
    UPLOADING,
    ANALYZING,
    ANALYZED,
    REVIEWED,
    REPORTED;

    // 각 상태에서 전이 가능한 다음 상태 집합. 근거:
    //  - CREATED → UPLOADING(업로드 시작), ANALYZING(업로드 없이 바로 선점되는 경로 허용)
    //  - UPLOADING → ANALYZING(분석 선점)
    //  - ANALYZING → ANALYZED(분석 성공) / CREATED·UPLOADING·ANALYZED(전체실패 롤백·큐포화 롤백·
    //    고착 복구·리퍼 복원 — 모두 직전 상태 statusBeforeAnalysis 로 되돌림, RECOVERY_STATUS=UPLOADING 포함)
    //  - ANALYZED → ANALYZING(재분석) / REVIEWED(검수 확정, 현재 전이 코드는 미구현이나 들어올 때
    //    이 테이블을 통해 안전 배선되도록 미리 허용)
    //  - REVIEWED → REPORTED(보고서화, 현재 미구현)
    //  - REPORTED → (종단, 추가 전이 없음)
    // 참고: CREATED/UPLOADING/ANALYZED → ANALYZING 선점은 실제로는 원자적 조건부 UPDATE
    // (InspectionRepository.startAnalyzingIfNotRunning)로 수행되어 advanceTo를 거치지 않지만,
    // 상태 머신의 정본(single source of truth)으로서 이 테이블에도 함께 명시해 둔다.
    private static final Map<InspectionStatus, Set<InspectionStatus>> ALLOWED_TRANSITIONS;

    static {
        Map<InspectionStatus, Set<InspectionStatus>> transitions = new EnumMap<>(InspectionStatus.class);
        transitions.put(CREATED, EnumSet.of(UPLOADING, ANALYZING));
        transitions.put(UPLOADING, EnumSet.of(ANALYZING));
        transitions.put(ANALYZING, EnumSet.of(CREATED, UPLOADING, ANALYZED));
        transitions.put(ANALYZED, EnumSet.of(ANALYZING, REVIEWED));
        transitions.put(REVIEWED, EnumSet.of(REPORTED));
        // REPORTED = 종단(추가 전이 없음). ⚠️ 다음 점검일 재계산 가드
        // (FacilityService#isStaleInspectionDate)가 이 종단성에 의존한다 — "REPORTED 회차는
        // 집계에서 절대 빠지지 않는다"를 전제로 max(REPORTED)를 최신 확정 회차로 쓰기 때문이다.
        // 훗날 REPORTED → 재작성/취소 전이를 열면 확정 회차가 집계에서 이탈해 낡은 회차가 다시
        // 다음 점검일을 덮어쓸 수 있으므로, 그 가드도 함께 재설계할 것(#1591 리뷰).
        transitions.put(REPORTED, EnumSet.noneOf(InspectionStatus.class));
        ALLOWED_TRANSITIONS = transitions;
    }

    /** 이 상태에서 {@code next}로의 전이가 허용 전이 테이블에 있는지. 자기 자신으로의 전이는 허용하지 않는다. */
    public boolean canTransitionTo(InspectionStatus next) {
        return ALLOWED_TRANSITIONS.getOrDefault(this, Set.of()).contains(next);
    }
}
