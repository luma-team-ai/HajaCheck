package com.hajacheck.core.defect.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DefectRevisionTest {

    @Test
    void record_변경내용과수정자를기록() {
        DefectRevision revision = DefectRevision.record(
                10L, 20L, "grade", "B", "C", "현장 재검토");

        assertThat(revision.getDefectId()).isEqualTo(10L);
        assertThat(revision.getRevisedBy()).isEqualTo(20L);
        assertThat(revision.getFieldChanged()).isEqualTo("grade");
        assertThat(revision.getOldValue()).isEqualTo("B");
        assertThat(revision.getNewValue()).isEqualTo("C");
        assertThat(revision.getReason()).isEqualTo("현장 재검토");
    }
}
