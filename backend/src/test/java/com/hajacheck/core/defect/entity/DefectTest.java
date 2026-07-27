package com.hajacheck.core.defect.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        Defect defect = Defect.builder().inspectionId(1L).type(DefectType.CRACK)
                .confidence(0.95).build();

        defect.changeStatus(DefectStatus.CONFIRMED);
        defect.changeStatus(DefectStatus.IN_PROGRESS);
        defect.changeStatus(DefectStatus.RESOLVED);

        assertThat(defect.getStatus()).isEqualTo(DefectStatus.RESOLVED);
        assertThat(defect.isReviewed()).isTrue();
    }

    @Test
    void changeStatus_사유없는건너뛰기와동일상태는거부하고해결상태는이탈불가() {
        Defect detected = Defect.builder().inspectionId(1L).type(DefectType.CRACK)
                .confidence(0.95).build();
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
                .confidence(0.95).build();

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
    void changeStatus_예외발생시reviewed는변경되지않음() {
        Defect defect = Defect.builder().inspectionId(1L).type(DefectType.CRACK)
                .confidence(0.95).build();
        assertThat(defect.isReviewed()).isFalse();

        assertThatThrownBy(() -> defect.changeStatus(DefectStatus.DETECTED))
                .isInstanceOf(IllegalStateException.class);

        assertThat(defect.isReviewed()).isFalse();
    }
}
