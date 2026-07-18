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
        defect.changeStatus(DefectStatus.ACTION_PENDING);
        defect.changeStatus(DefectStatus.IN_PROGRESS);
        defect.changeStatus(DefectStatus.RESOLVED);

        assertThat(defect.getStatus()).isEqualTo(DefectStatus.RESOLVED);
    }

    @Test
    void changeStatus_건너뛰기역행동일상태및해결상태이탈을거부() {
        Defect detected = Defect.builder().inspectionId(1L).type(DefectType.CRACK)
                .confidence(0.95).build();
        assertThatThrownBy(() -> detected.changeStatus(DefectStatus.ACTION_PENDING))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> detected.changeStatus(DefectStatus.DETECTED))
                .isInstanceOf(IllegalStateException.class);

        Defect resolved = Defect.builder().inspectionId(1L).type(DefectType.CRACK)
                .confidence(0.95).status(DefectStatus.RESOLVED).build();
        assertThatThrownBy(() -> resolved.changeStatus(DefectStatus.IN_PROGRESS))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> resolved.changeStatus(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void softDelete_물리삭제대신삭제표시() {
        Defect defect = Defect.builder().inspectionId(1L).type(DefectType.SPALLING)
                .confidence(0.8).build();

        defect.softDelete();
        defect.softDelete();

        assertThat(defect.isDeleted()).isTrue();
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
}
