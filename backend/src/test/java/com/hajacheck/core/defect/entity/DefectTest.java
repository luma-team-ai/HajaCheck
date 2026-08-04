package com.hajacheck.core.defect.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DefectTest {

    @Test
    void review_등급을반영하고검토완료로변경() {
        Defect defect = Defect.builder().inspectionId(1L).type(DefectType.CRACK)
                .confidence(0.95).build();

        defect.review(DefectGrade.C);

        assertThat(defect.getGrade()).isEqualTo(DefectGrade.C);
        assertThat(defect.isReviewed()).isTrue();
    }

    @Test
    void review_해결된결함이면예외() {
        Defect defect = Defect.builder().inspectionId(1L).type(DefectType.CRACK)
                .confidence(0.95).status(DefectStatus.RESOLVED).build();

        assertThatThrownBy(() -> defect.review(DefectGrade.C))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void changeStatus_정의된수명주기순서로전이() {
        // 등급은 전이의 선행조건일 뿐 이 테스트의 관심사가 아니다(#1397).
        Defect defect = Defect.builder().inspectionId(1L).type(DefectType.CRACK)
                .confidence(0.95).grade(DefectGrade.C).build();

        defect.changeStatus(DefectStatus.CONFIRMED);
        defect.changeStatus(DefectStatus.IN_PROGRESS);
        defect.changeStatus(DefectStatus.RESOLVED);

        assertThat(defect.getStatus()).isEqualTo(DefectStatus.RESOLVED);
        assertThat(defect.isReviewed()).isTrue();
    }

    @Test
    void changeStatus_사유없는건너뛰기와동일상태는거부하고해결상태는이탈불가() {
        Defect detected = Defect.builder().inspectionId(1L).type(DefectType.CRACK)
                .confidence(0.95).grade(DefectGrade.C).build();
        assertThatThrownBy(() -> detected.changeStatus(DefectStatus.IN_PROGRESS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> detected.changeStatus(DefectStatus.DETECTED))
                .isInstanceOf(IllegalStateException.class);

        Defect resolved = Defect.builder().inspectionId(1L).type(DefectType.CRACK)
                .confidence(0.95).status(DefectStatus.RESOLVED).build();
        assertThatThrownBy(() -> resolved.changeStatus(DefectStatus.IN_PROGRESS, "재검토 필요"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> resolved.changeStatus(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void changeStatus_사유가있으면건너뛰기와역행을허용() {
        Defect detected = Defect.builder().inspectionId(1L).type(DefectType.CRACK)
                .confidence(0.95).grade(DefectGrade.C).build();

        detected.changeStatus(DefectStatus.IN_PROGRESS, "경미한 하자라 검수확정 생략");
        assertThat(detected.getStatus()).isEqualTo(DefectStatus.IN_PROGRESS);
        assertThat(detected.isReviewed()).isTrue();

        detected.changeStatus(DefectStatus.CONFIRMED, "확정 이전으로 재검토 필요");
        assertThat(detected.getStatus()).isEqualTo(DefectStatus.CONFIRMED);
        assertThat(detected.isReviewed()).isTrue();
    }

    @Test
    void changeStatus_사유가공백이면건너뛰기와역행을거부() {
        Defect defect = Defect.builder().inspectionId(1L).type(DefectType.CRACK)
                .confidence(0.95).build();

        assertThatThrownBy(() -> defect.changeStatus(DefectStatus.IN_PROGRESS, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void changeStatus_검수확정에서_조치완료로_바로건너뛰면_사유없이는거부() {
        // 4단계 축소(V21) 후 CONFIRMED 의 정방향 한 단계는 IN_PROGRESS 다. CONFIRMED → RESOLVED 는
        // 조치중을 건너뛰는 스킵 전이이므로 사유 없이는 막혀야 한다 — "조치 없이 완료 처리" 방지 회귀선.
        Defect confirmed = Defect.builder().inspectionId(1L).type(DefectType.CRACK)
                .confidence(0.95).status(DefectStatus.CONFIRMED).build();

        assertThatThrownBy(() -> confirmed.changeStatus(DefectStatus.RESOLVED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(confirmed.getStatus()).isEqualTo(DefectStatus.CONFIRMED);

        // 사유가 있으면 기존 규칙대로 허용된다(역행/건너뛰기 공통 규칙).
        confirmed.changeStatus(DefectStatus.RESOLVED, "경미한 하자라 현장에서 즉시 조치 완료");
        assertThat(confirmed.getStatus()).isEqualTo(DefectStatus.RESOLVED);
    }

    @Test
    void softDelete_물리삭제대신삭제표시() {
        Defect defect = Defect.builder().inspectionId(1L).type(DefectType.SPALLING)
                .confidence(0.8).build();

        defect.softDelete();
        defect.softDelete();

        assertThat(defect.isDeleted()).isTrue();
        assertThat(defect.isReviewed()).isTrue();
    }

    @Test
    void restore_등급없이삭제됐던하자는복구후미확정으로돌아간다() {
        // 등급 확정(review) 없이 미확정 상태에서 오탐 삭제된 경우 — softDelete가 세운
        // reviewed=true를 그대로 두면 실제로는 한 번도 검수하지 않은 하자가 검수완료로
        // 부활해 진행률·confirmReview 가드를 속인다(실측 버그, restore() 참고).
        Defect defect = Defect.builder().inspectionId(1L).type(DefectType.CRACK)
                .confidence(0.9).build();

        defect.softDelete();
        defect.restore();

        assertThat(defect.isDeleted()).isFalse();
        assertThat(defect.isReviewed()).isFalse();
    }

    @Test
    void restore_등급확정후삭제됐던하자는복구후검수완료를유지한다() {
        Defect defect = Defect.builder().inspectionId(1L).type(DefectType.CRACK)
                .confidence(0.9).build();
        defect.review(DefectGrade.C);

        defect.softDelete();
        defect.restore();

        assertThat(defect.isDeleted()).isFalse();
        assertThat(defect.isReviewed()).isTrue();
        assertThat(defect.getGrade()).isEqualTo(DefectGrade.C);
    }

    @Test
    void updateCrackMeasurement_진행중결함의측정값을갱신() {
        Defect defect = Defect.builder().inspectionId(1L).type(DefectType.CRACK)
                .confidence(0.95).build();

        defect.updateCrackMeasurement(0.4, 120.0);

        assertThat(defect.getCrackWidthMm()).isEqualTo(0.4);
        assertThat(defect.getCrackLengthMm()).isEqualTo(120.0);
    }

    @Test
    void updateCrackMeasurement_해결되었거나삭제된결함이면예외() {
        Defect resolved = Defect.builder().inspectionId(1L).type(DefectType.CRACK)
                .confidence(0.95).status(DefectStatus.RESOLVED).build();
        Defect deleted = Defect.builder().inspectionId(2L).type(DefectType.CRACK)
                .confidence(0.9).build();
        deleted.softDelete();

        assertThatThrownBy(() -> resolved.updateCrackMeasurement(0.4, 120.0))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> deleted.updateCrackMeasurement(0.4, 120.0))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void review_등급이없으면검토상태를변경하지않고예외() {
        Defect defect = Defect.builder().inspectionId(1L).type(DefectType.CRACK)
                .confidence(0.95).build();

        assertThatThrownBy(() -> defect.review(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(defect.getGrade()).isNull();
        assertThat(defect.isReviewed()).isFalse();
    }

    @Test
    void 삭제된결함_검토와상태변경을거부() {
        Defect defect = Defect.builder().inspectionId(1L).type(DefectType.CRACK)
                .confidence(0.95).build();
        defect.softDelete();

        assertThatThrownBy(() -> defect.review(DefectGrade.C))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> defect.changeStatus(DefectStatus.CONFIRMED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void updateLocation_값을그대로반영() {
        Defect defect = Defect.builder().inspectionId(1L).type(DefectType.CRACK)
                .confidence(0.95).build();

        defect.updateLocation("외벽 동측 12층 부근");

        assertThat(defect.getLocation()).isEqualTo("외벽 동측 12층 부근");
    }

    @Test
    void updateLocation_빈문자열이나공백은null로정규화() {
        Defect defect = Defect.builder().inspectionId(1L).type(DefectType.CRACK)
                .confidence(0.95).location("기존 위치").build();

        defect.updateLocation("");
        assertThat(defect.getLocation()).isNull();

        defect.updateLocation("기존 위치");
        defect.updateLocation("   ");
        assertThat(defect.getLocation()).isNull();
    }

    @Test
    void updateLocation_삭제된결함이면예외() {
        Defect defect = Defect.builder().inspectionId(1L).type(DefectType.CRACK)
                .confidence(0.95).build();
        defect.softDelete();

        assertThatThrownBy(() -> defect.updateLocation("아무 위치"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void confirmPreviousDefect_이전회차하자id를반영() {
        Defect defect = Defect.builder().inspectionId(1L).type(DefectType.CRACK)
                .confidence(0.95).build();

        defect.confirmPreviousDefect(99L);

        assertThat(defect.getPreviousDefectId()).isEqualTo(99L);
    }

    @Test
    void confirmPreviousDefect_삭제된결함이면예외() {
        Defect defect = Defect.builder().inspectionId(1L).type(DefectType.CRACK)
                .confidence(0.95).build();
        defect.softDelete();

        assertThatThrownBy(() -> defect.confirmPreviousDefect(99L))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── #1128: 조치 결과 등록의 전이 목표(targetStatus) 가변화 ──

    private Defect defectWithStatus(DefectStatus status) {
        return Defect.builder().inspectionId(1L).type(DefectType.CRACK)
                .confidence(0.95).status(status).build();
    }

    @Test
    void registerActionResult_CONFIRMED에서_IN_PROGRESS로전이하고조치필드저장() {
        Defect defect = defectWithStatus(DefectStatus.CONFIRMED);

        defect.registerActionResult(50L, "1차 보수 착수", LocalDate.of(2026, 7, 28), 200L,
                DefectStatus.IN_PROGRESS);

        assertThat(defect.getStatus()).isEqualTo(DefectStatus.IN_PROGRESS);
        assertThat(defect.getActionMediaId()).isEqualTo(50L);
        assertThat(defect.getActionContent()).isEqualTo("1차 보수 착수");
        assertThat(defect.getActionDate()).isEqualTo(LocalDate.of(2026, 7, 28));
        assertThat(defect.getActionAssigneeId()).isEqualTo(200L);
    }

    @Test
    void registerActionResult_IN_PROGRESS에서_RESOLVED로전이하고조치필드저장() {
        Defect defect = defectWithStatus(DefectStatus.IN_PROGRESS);

        defect.registerActionResult(51L, "보수 완료", LocalDate.of(2026, 7, 28), 201L,
                DefectStatus.RESOLVED);

        assertThat(defect.getStatus()).isEqualTo(DefectStatus.RESOLVED);
        assertThat(defect.getActionMediaId()).isEqualTo(51L);
        assertThat(defect.getActionAssigneeId()).isEqualTo(201L);
    }

    @Test
    void registerActionResult_CONFIRMED에서_RESOLVED는건너뛴전이라거부되고조치필드도남지않음() {
        Defect defect = defectWithStatus(DefectStatus.CONFIRMED);

        assertThatThrownBy(() -> defect.registerActionResult(50L, "조기 완료 시도",
                LocalDate.of(2026, 7, 28), 200L, DefectStatus.RESOLVED))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(defect.getStatus()).isEqualTo(DefectStatus.CONFIRMED);
        // changeStatus()가 먼저 실행되므로 실패 시 조치 필드는 전혀 채워지지 않아야 한다.
        assertThat(defect.getActionContent()).isNull();
        assertThat(defect.getActionMediaId()).isNull();
    }

    // ── #1193/HAJA-569: 조치중(IN_PROGRESS) 유지 재제출 허용 ──

    @Test
    void registerActionResult_IN_PROGRESS유지재제출은상태그대로필드만갱신() {
        Defect defect = defectWithStatus(DefectStatus.IN_PROGRESS);

        defect.registerActionResult(50L, "1차 보수", LocalDate.of(2026, 7, 28), 200L,
                DefectStatus.IN_PROGRESS);
        defect.registerActionResult(51L, "2차 보수", LocalDate.of(2026, 7, 29), 201L,
                DefectStatus.IN_PROGRESS);

        assertThat(defect.getStatus()).isEqualTo(DefectStatus.IN_PROGRESS);
        assertThat(defect.getActionMediaId()).isEqualTo(51L);
        assertThat(defect.getActionContent()).isEqualTo("2차 보수");
        assertThat(defect.getActionAssigneeId()).isEqualTo(201L);
    }

    @Test
    void registerActionResult_RESOLVED유지재제출은종료상태이탈금지규칙으로거부() {
        // RESOLVED는 changeStatus()에 그대로 위임되므로 "이탈 금지" 검사가 먼저 걸려
        // IllegalStateException(DomainStateTransitionException)으로 막힌다 — 회귀 방지.
        Defect defect = defectWithStatus(DefectStatus.RESOLVED);

        assertThatThrownBy(() -> defect.registerActionResult(50L, "재등록 시도",
                LocalDate.of(2026, 7, 28), 200L, DefectStatus.RESOLVED))
                .isInstanceOf(IllegalStateException.class);
        assertThat(defect.getActionContent()).isNull();
    }

    @Test
    void registerActionResult_삭제된하자는IN_PROGRESS유지재제출도거부() {
        Defect defect = defectWithStatus(DefectStatus.IN_PROGRESS);
        defect.softDelete();

        assertThatThrownBy(() -> defect.registerActionResult(50L, "삭제 후 시도",
                LocalDate.of(2026, 7, 28), 200L, DefectStatus.IN_PROGRESS))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void changeStatus_예외발생시reviewed는변경되지않음() {
        Defect defect = Defect.builder().inspectionId(1L).type(DefectType.CRACK)
                .confidence(0.95).grade(DefectGrade.C).build();
        assertThat(defect.isReviewed()).isFalse();

        assertThatThrownBy(() -> defect.changeStatus(DefectStatus.DETECTED))
                .isInstanceOf(IllegalStateException.class);

        assertThat(defect.isReviewed()).isFalse();
    }

    @Test
    void changeStatus_등급없는신규결함은어떤상태로도이탈불가() {
        // #1397 — 등급 없이 확정되면 화면의 '등급 수정'은 DETECTED에서만 열리므로 이후 어디서도
        // 등급을 부여할 수 없는 영구 미분류로 고착된다. 확정 전에 review()로 등급을 먼저 매겨야 한다.
        Defect ungraded = Defect.builder().inspectionId(1L).type(DefectType.CRACK)
                .confidence(0.95).build();

        assertThatThrownBy(() -> ungraded.changeStatus(DefectStatus.CONFIRMED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("등급");
        // 사유를 붙인 건너뛰기로도 우회할 수 없다.
        assertThatThrownBy(() -> ungraded.changeStatus(DefectStatus.IN_PROGRESS, "검수확정 생략"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ungraded.getStatus()).isEqualTo(DefectStatus.DETECTED);
        assertThat(ungraded.isReviewed()).isFalse();

        // 등급을 매기면(review는 status를 바꾸지 않는다) 정상 전이된다.
        ungraded.review(DefectGrade.D);
        ungraded.changeStatus(DefectStatus.CONFIRMED);
        assertThat(ungraded.getStatus()).isEqualTo(DefectStatus.CONFIRMED);
    }
}
