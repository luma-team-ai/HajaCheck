package com.hajacheck.core.defect.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DefectStatusTest {

    @Test
    void 선언순서가진행순서라는불변식을고정한다() {
        // isAtOrAfter()가 ordinal 비교이므로, enum 중간에 비선형 값(예: REJECTED)이 끼어들면 조용히
        // 틀어진다(#1583 리뷰 P3). changeStatus/isForwardStepTo의 exhaustive switch는 상수 "추가"엔
        // 컴파일 에러로 신호를 주지만 "순서 재배치"엔 무신호이므로, 순서 자체를 여기서 못박는다.
        assertThat(DefectStatus.values()).containsExactly(
                DefectStatus.DETECTED,
                DefectStatus.CONFIRMED,
                DefectStatus.IN_PROGRESS,
                DefectStatus.RESOLVED);
    }

    @Test
    void isAtOrAfter_같거나더진행된단계면true() {
        assertThat(DefectStatus.RESOLVED.isAtOrAfter(DefectStatus.CONFIRMED)).isTrue();
        assertThat(DefectStatus.CONFIRMED.isAtOrAfter(DefectStatus.CONFIRMED)).isTrue();
        assertThat(DefectStatus.DETECTED.isAtOrAfter(DefectStatus.CONFIRMED)).isFalse();
    }

    @Test
    void isForwardStepTo_정방향한단계만true() {
        assertThat(DefectStatus.DETECTED.isForwardStepTo(DefectStatus.CONFIRMED)).isTrue();
        assertThat(DefectStatus.CONFIRMED.isForwardStepTo(DefectStatus.IN_PROGRESS)).isTrue();
        assertThat(DefectStatus.IN_PROGRESS.isForwardStepTo(DefectStatus.RESOLVED)).isTrue();
        // 제자리·건너뛰기·역행·종료 단계 이탈은 모두 false
        assertThat(DefectStatus.CONFIRMED.isForwardStepTo(DefectStatus.CONFIRMED)).isFalse();
        assertThat(DefectStatus.CONFIRMED.isForwardStepTo(DefectStatus.RESOLVED)).isFalse();
        assertThat(DefectStatus.IN_PROGRESS.isForwardStepTo(DefectStatus.CONFIRMED)).isFalse();
        assertThat(DefectStatus.RESOLVED.isForwardStepTo(DefectStatus.IN_PROGRESS)).isFalse();
    }
}
