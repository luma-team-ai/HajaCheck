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

        assertThat(defect.isDeleted()).isTrue();
    }
}
