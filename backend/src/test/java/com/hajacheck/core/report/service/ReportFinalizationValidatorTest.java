package com.hajacheck.core.report.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReportFinalizationValidatorTest {

    private final ReportFinalizationValidator validator = new ReportFinalizationValidator();

    @Test
    @DisplayName("details 섹션이 제외된 경우 하자 상세 항목이 없어도 검증을 통과한다")
    void details_제외시_하자상세_없어도_확정가능() {
        String contentJson = """
                {
                    "overview": {
                        "purpose": "정기점검",
                        "facility_summary": "시설물 개요",
                        "scope": "전체"
                    },
                    "summary": {
                        "overall_opinion": "양호"
                    },
                    "detail": {
                        "items": []
                    },
                    "recommendation": {
                        "items": [
                            {"method": "표면처리"}
                        ]
                    },
                    "reportOptions": {
                        "sections": ["overview", "summary", "recommendation"]
                    }
                }
                """;

        assertThatCode(() -> validator.validate(contentJson))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("details 섹션이 포함된 경우 하자 상세 항목이 없으면 검증에 실패한다")
    void details_포함시_하자상세_없으면_확정불가() {
        String contentJson = """
                {
                    "overview": {
                        "purpose": "정기점검",
                        "facility_summary": "시설물 개요",
                        "scope": "전체"
                    },
                    "summary": {
                        "overall_opinion": "양호"
                    },
                    "detail": {
                        "items": []
                    },
                    "recommendation": {
                        "items": [
                            {"method": "표면처리"}
                        ]
                    },
                    "reportOptions": {
                        "sections": ["overview", "summary", "details", "recommendation"]
                    }
                }
                """;

        assertThatThrownBy(() -> validator.validate(contentJson))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING);
    }

    @Test
    @DisplayName("기본현황 점검 목적이 비어있으면 검증에 실패한다")
    void overview_필수항목_누락시_확정불가() {
        String contentJson = """
                {
                    "overview": {
                        "purpose": "",
                        "facility_summary": "시설물 개요",
                        "scope": "전체"
                    },
                    "summary": {
                        "overall_opinion": "양호"
                    },
                    "detail": {
                        "items": [{"description": "균열", "cause": "건조수축"}]
                    },
                    "recommendation": {
                        "items": [{"method": "표면처리"}]
                    }
                }
                """;

        assertThatThrownBy(() -> validator.validate(contentJson))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING);
    }
}
