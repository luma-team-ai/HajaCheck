package com.hajacheck.core.defect.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DefectTest {

    @Test
    void review_등급을반영하고검토완료로변경() {
        Defect defect = Defect.builder()
                .inspectionId(1L)
                .type(DefectType.CRACK)
                .confidence(0.95)
                .build();

        defect.review(DefectGrade.C);

        assertThat(defect.getGrade()).isEqualTo(DefectGrade.C);
        assertThat(defect.isReviewed()).isTrue();
    }

    @Test
    void softDelete_물리삭제대신삭제표시() {
        Defect defect = Defect.builder()
                .inspectionId(1L)
                .type(DefectType.SPALLING)
                .confidence(0.8)
                .build();

        defect.softDelete();

        assertThat(defect.isDeleted()).isTrue();
    }
}
