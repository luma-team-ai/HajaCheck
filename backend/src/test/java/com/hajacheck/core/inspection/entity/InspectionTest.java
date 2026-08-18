package com.hajacheck.core.inspection.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * {@link Inspection#applyPerformedAt} 단위 검증(V43, #1667) — MediaWriter가 회차의 첫 INSPECTION_SOURCE
 * 미디어 저장 직후 호출하는 자동 세팅 규칙: null 후보는 무시, 값이 없으면 그대로 세팅, 값이 있으면 더
 * 이른 시각일 때만 갱신(늦은 값으로 덮지 않음).
 */
class InspectionTest {

    private Inspection newInspection() {
        return Inspection.builder()
                .facilityId(1L)
                .createdBy(1L)
                .assignedInspectorId(1L)
                .roundNo(1)
                .inspectionDate(LocalDate.of(2026, 8, 18))
                .build();
    }

    @Test
    void applyPerformedAt_최초에는_후보값그대로_세팅된다() {
        Inspection inspection = newInspection();
        LocalDateTime candidate = LocalDateTime.of(2026, 8, 18, 9, 0);

        inspection.applyPerformedAt(candidate);

        assertThat(inspection.getPerformedAt()).isEqualTo(candidate);
    }

    @Test
    void applyPerformedAt_null후보는_무시하고_기존값유지() {
        Inspection inspection = newInspection();
        LocalDateTime existing = LocalDateTime.of(2026, 8, 18, 9, 0);
        inspection.applyPerformedAt(existing);

        inspection.applyPerformedAt(null);

        assertThat(inspection.getPerformedAt()).isEqualTo(existing);
    }

    @Test
    void applyPerformedAt_기존값보다_더이른후보면_갱신된다() {
        Inspection inspection = newInspection();
        LocalDateTime later = LocalDateTime.of(2026, 8, 18, 15, 0);
        LocalDateTime earlier = LocalDateTime.of(2026, 8, 18, 9, 0);
        inspection.applyPerformedAt(later);

        inspection.applyPerformedAt(earlier);

        assertThat(inspection.getPerformedAt()).isEqualTo(earlier);
    }

    @Test
    void applyPerformedAt_기존값보다_늦은후보는_무시하고_덮지않는다() {
        Inspection inspection = newInspection();
        LocalDateTime earlier = LocalDateTime.of(2026, 8, 18, 9, 0);
        LocalDateTime later = LocalDateTime.of(2026, 8, 18, 15, 0);
        inspection.applyPerformedAt(earlier);

        inspection.applyPerformedAt(later);

        assertThat(inspection.getPerformedAt()).isEqualTo(earlier);
    }
}
