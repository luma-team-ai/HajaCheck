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

    /**
     * {@code next}가 이 상태의 <b>정방향 한 단계</b> 다음인지 판정한다 — 상태 전이 규칙의 단일 기준으로,
     * {@link Defect#changeStatus(DefectStatus, String)}("정방향 한 단계만 사유 없이 허용")과
     * updateStatus() 그룹 팬아웃("정방향 한 단계인 멤버만 따라간다")이 함께 쓴다.
     *
     * <p>ordinal 산술이 아니라 exhaustive switch로 구현한 것은 의도적이다 — 상수가 추가되면 여기서
     * 컴파일 에러가 나 "다음 단계가 무엇인지"를 반드시 다시 정의하게 만든다(무신호 오동작 방지).
     *
     * <p>{@code next}가 null이면 항상 false다 — RESOLVED는 정방향 다음이 없어({@code expectedNext}가
     * null) null 비교가 그대로 통과하면 "종료 상태에서 null로 전진 가능"이라는 오답이 된다.
     */
    public boolean isForwardStepTo(DefectStatus next) {
        if (next == null) {
            return false;
        }
        DefectStatus expectedNext = switch (this) {
            case DETECTED -> CONFIRMED;
            case CONFIRMED -> IN_PROGRESS;
            case IN_PROGRESS -> RESOLVED;
            case RESOLVED -> null; // 종료 단계 — 정방향 다음이 없다
        };
        return next == expectedNext;
    }
}
