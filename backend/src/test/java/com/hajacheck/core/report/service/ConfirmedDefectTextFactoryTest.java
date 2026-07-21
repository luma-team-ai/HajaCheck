package com.hajacheck.core.report.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.core.ai.dto.ReportRequest;
import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectType;
import org.junit.jupiter.api.Test;

class ConfirmedDefectTextFactoryTest {

    @Test
    void from_균열_치수를포함한한국어문장을생성() {
        Defect defect = Defect.builder()
                .inspectionId(1L)
                .type(DefectType.CRACK)
                .confidence(0.9)
                .grade(DefectGrade.C)
                .crackWidthMm(3.0)
                .crackLengthMm(20.0)
                .build();

        ReportRequest.ConfirmedDefect result = ConfirmedDefectTextFactory.from(defect, "서울시 강남구");

        assertThat(result.defectType()).isEqualTo("균열");
        assertThat(result.location()).isEqualTo("서울시 강남구");
        assertThat(result.severityGrade()).isEqualTo("C");
        assertThat(result.description()).contains("균열").contains("C").contains("3.0mm").contains("20.0mm");
    }

    @Test
    void from_균열이지만치수미측정이면미측정으로표기() {
        Defect defect = Defect.builder()
                .inspectionId(1L)
                .type(DefectType.CRACK)
                .confidence(0.9)
                .grade(DefectGrade.B)
                .build();

        ReportRequest.ConfirmedDefect result = ConfirmedDefectTextFactory.from(defect, "서울시 강남구");

        assertThat(result.description()).contains("미측정");
    }

    @Test
    void from_비균열_등급판정문구를생성() {
        Defect defect = Defect.builder()
                .inspectionId(1L)
                .type(DefectType.LEAK_EFFLORESCENCE)
                .confidence(0.9)
                .grade(DefectGrade.B)
                .build();

        ReportRequest.ConfirmedDefect result = ConfirmedDefectTextFactory.from(defect, "부산시 해운대구");

        assertThat(result.defectType()).isEqualTo("누수·백태");
        assertThat(result.severityGrade()).isEqualTo("B");
        assertThat(result.description()).isEqualTo("누수·백태(등급 B)로 판정됨");
    }

    @Test
    void from_등급이없으면미분류로표기() {
        Defect defect = Defect.builder()
                .inspectionId(1L)
                .type(DefectType.SPALLING)
                .confidence(0.9)
                .build();

        ReportRequest.ConfirmedDefect result = ConfirmedDefectTextFactory.from(defect, "인천시 남동구");

        assertThat(result.severityGrade()).isEqualTo("미분류");
        assertThat(result.description()).isEqualTo("박리·박락(등급 미분류)로 판정됨");
    }
}
