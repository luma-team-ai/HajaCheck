package com.hajacheck.core.defect.entity;

/**
 * 결함 조치 상태 — DDL defect_status_type(탐지됨/확인됨/조치중/해결됨).
 *
 * <p>CONFIRMED(검수확정)가 "검수완료 + 아직 조치 착수 전"까지 겸한다 — 구 ACTION_PENDING(조치대기)은
 * CONFIRMED로 흡수되어 제거됐다(V19 마이그레이션).
 *
 * <p><b>선언 순서 = 진행 순서다(DETECTED → CONFIRMED → IN_PROGRESS → RESOLVED, 분기 없는 4단계
 * 선형).</b> {@link com.hajacheck.core.defect.entity.Defect#changeStatus(DefectStatus, String)}의
 * 정방향 판정도 이 순서를 그대로 따른다. {@link #isAtOrAfter(DefectStatus)}가 이 불변식에 의존하므로,
 * 상수를 추가·재배치할 때는 반드시 진행 순서대로 넣어야 한다.
 */
public enum DefectStatus {
    DETECTED,
    CONFIRMED,
    IN_PROGRESS,
    RESOLVED;

    /**
     * 이 상태가 {@code other}와 같거나 그보다 더 진행된 단계인지(= 진행이 앞서 있는지) 판정한다.
     * 위 "선언 순서 = 진행 순서" 불변식에 기대어 ordinal을 비교한다.
     */
    public boolean isAtOrAfter(DefectStatus other) {
        return this.ordinal() >= other.ordinal();
    }
}
