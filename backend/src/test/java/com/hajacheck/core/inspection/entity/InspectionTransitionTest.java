package com.hajacheck.core.inspection.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hajacheck.global.exception.DomainStateTransitionException;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 점검 상태 머신 불변식 고정(코드 리뷰 종결) — 개별 시나리오가 아니라 "허용 전이 테이블에 없는
 * 임의의 전이는 항상 거부된다"를 전수 검증한다. 허용 전이가 늘거나 바뀌면 이 기대 테이블도 함께
 * 갱신해야 하며, 그때 프로덕션 테이블과의 불일치가 이 테스트로 드러난다.
 */
class InspectionTransitionTest {

    // 프로덕션 InspectionStatus.ALLOWED_TRANSITIONS 를 그대로 복제한 기대값(테스트로 테이블을 고정).
    private static final Map<InspectionStatus, Set<InspectionStatus>> EXPECTED_ALLOWED;

    static {
        Map<InspectionStatus, Set<InspectionStatus>> m = new EnumMap<>(InspectionStatus.class);
        m.put(InspectionStatus.CREATED, EnumSet.of(InspectionStatus.UPLOADING, InspectionStatus.ANALYZING));
        m.put(InspectionStatus.UPLOADING, EnumSet.of(InspectionStatus.ANALYZING));
        m.put(InspectionStatus.ANALYZING,
                EnumSet.of(InspectionStatus.CREATED, InspectionStatus.UPLOADING, InspectionStatus.ANALYZED));
        m.put(InspectionStatus.ANALYZED, EnumSet.of(InspectionStatus.ANALYZING, InspectionStatus.REVIEWED));
        m.put(InspectionStatus.REVIEWED, EnumSet.of(InspectionStatus.REPORTED));
        m.put(InspectionStatus.REPORTED, EnumSet.noneOf(InspectionStatus.class));
        EXPECTED_ALLOWED = m;
    }

    private Inspection inspectionWithStatus(InspectionStatus status) {
        return Inspection.builder()
                .facilityId(1L)
                .createdBy(1L)
                .assignedInspectorId(1L)
                .roundNo(1)
                .inspectionDate(LocalDate.of(2026, 7, 1))
                .status(status)
                .build();
    }

    @Test
    void canTransitionTo_전조합이_허용테이블과정확히일치한다() {
        for (InspectionStatus from : InspectionStatus.values()) {
            for (InspectionStatus to : InspectionStatus.values()) {
                boolean expected = EXPECTED_ALLOWED.get(from).contains(to);
                assertThat(from.canTransitionTo(to))
                        .as("%s -> %s 허용 여부", from, to)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    void advanceTo_허용전이는_상태를바꾼다() {
        Inspection inspection = inspectionWithStatus(InspectionStatus.ANALYZING);

        inspection.advanceTo(InspectionStatus.ANALYZED);

        assertThat(inspection.getStatus()).isEqualTo(InspectionStatus.ANALYZED);
    }

    @Test
    void advanceTo_허용되지않은전이는_DomainStateTransitionException으로거부하고_상태를유지한다() {
        // 예: 리퍼가 UPLOADING으로 되돌린 회차를 좀비 워커가 ANALYZED로 되살리려는 전이 —
        // 검증 없는 setter였을 때는 조용히 적용됐다. 이제는 거부되고 상태가 그대로 남는다(fail-safe).
        // 코드 리뷰 P2(2차) — 표준 IllegalStateException이 아니라 DomainStateTransitionException을
        // 던져야 GlobalExceptionHandler가 409(INVALID_STATE_TRANSITION)로 매핑한다. 표준
        // IllegalStateException은 "프로그래밍 오류"로 간주돼 500으로 처리되므로, 동시성 경쟁으로
        // 예상 가능한 이 거부가 500으로 노출되면 안 된다.
        Inspection inspection = inspectionWithStatus(InspectionStatus.UPLOADING);

        assertThatThrownBy(() -> inspection.advanceTo(InspectionStatus.ANALYZED))
                .isInstanceOf(DomainStateTransitionException.class);
        assertThat(inspection.getStatus()).isEqualTo(InspectionStatus.UPLOADING);
    }

    @Test
    void advanceTo_자기자신으로의전이도_DomainStateTransitionException으로거부한다() {
        Inspection inspection = inspectionWithStatus(InspectionStatus.ANALYZING);

        assertThatThrownBy(() -> inspection.advanceTo(InspectionStatus.ANALYZING))
                .isInstanceOf(DomainStateTransitionException.class);
    }
}
