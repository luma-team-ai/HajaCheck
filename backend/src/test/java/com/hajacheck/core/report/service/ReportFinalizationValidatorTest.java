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
    @DisplayName("정상 전체 보고서(자동 섹션 및 모든 수동 섹션 포함) 확정 성공")
    void 정상_전체보고서_확정성공() {
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
                        "items": [
                            {"description": "균열 발생", "cause": "건조 수축"}
                        ]
                    },
                    "recommendation": {
                        "items": [
                            {"method": "에폭시 수지 주입"}
                        ]
                    },
                    "reportOptions": {
                        "sections": ["overview", "summary", "details", "recommendation"]
                    },
                    "manualSections": [
                        {
                            "id": "m1",
                            "type": "submission",
                            "title": "제출문",
                            "data": {
                                "recipient": "홍길동",
                                "contractDate": "2026-08-01",
                                "companyName": "(주)하자체크",
                                "companyAddress": "서울시 강남구",
                                "representativeName": "대표자"
                            }
                        },
                        {
                            "id": "m2",
                            "type": "participants",
                            "title": "참여자",
                            "data": {
                                "entries": [
                                    {
                                        "role": "책임기술자",
                                        "name": "김점검",
                                        "qualification": "특급기술자",
                                        "period": "2026.08.01~2026.08.05"
                                    }
                                ]
                            }
                        },
                        {
                            "id": "m3",
                            "type": "location-drawing-photos",
                            "title": "위치 및 도면 사진",
                            "data": {
                                "images": ["https://example.com/img1.jpg"]
                            }
                        },
                        {
                            "id": "m4",
                            "type": "custom-notes",
                            "title": "기타 수동 섹션",
                            "data": {
                                "body": "특이사항 작성"
                            }
                        }
                    ]
                }
                """;

        assertThatCode(() -> validator.validate(contentJson))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("overview 섹션 제외 시 검증 실패")
    void overview_제외시_확정불가() {
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
                    "reportOptions": {
                        "sections": ["summary", "details"]
                    }
                }
                """;

        assertThatThrownBy(() -> validator.validate(contentJson))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING);
    }

    @Test
    @DisplayName("summary 섹션 제외 시 검증 실패")
    void summary_제외시_확정불가() {
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
                    "reportOptions": {
                        "sections": ["overview", "details"]
                    }
                }
                """;

        assertThatThrownBy(() -> validator.validate(contentJson))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING);
    }

    @Test
    @DisplayName("알 수 없는 section 값 포함 시 검증 실패")
    void 알수없는_section값_확정불가() {
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
                    "reportOptions": {
                        "sections": ["overview", "summary", "unknown_section"]
                    }
                }
                """;

        assertThatThrownBy(() -> validator.validate(contentJson))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING);
    }

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
    @DisplayName("details 항목의 description 또는 cause 누락 시 검증 실패")
    void details_필수필드_누락시_확정불가() {
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
                        "items": [
                            {"description": "균열", "cause": " "}
                        ]
                    },
                    "reportOptions": {
                        "sections": ["overview", "summary", "details"]
                    }
                }
                """;

        assertThatThrownBy(() -> validator.validate(contentJson))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING);
    }

    @Test
    @DisplayName("recommendation 항목의 method 누락 시 검증 실패")
    void recommendation_필수필드_누락시_확정불가() {
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
                    "recommendation": {
                        "items": [
                            {"method": ""}
                        ]
                    },
                    "reportOptions": {
                        "sections": ["overview", "summary", "recommendation"]
                    }
                }
                """;

        assertThatThrownBy(() -> validator.validate(contentJson))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING);
    }

    @Test
    @DisplayName("submission 수동 섹션의 필수 필드 누락 시 검증 실패")
    void submission_수동섹션_누락시_확정불가() {
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
                    "manualSections": [
                        {
                            "id": "m1",
                            "type": "submission",
                            "title": "제출문",
                            "data": {
                                "recipient": "홍길동",
                                "contractDate": "",
                                "companyName": "(주)하자체크",
                                "companyAddress": "서울시 강남구",
                                "representativeName": "대표자"
                            }
                        }
                    ]
                }
                """;

        assertThatThrownBy(() -> validator.validate(contentJson))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING);
    }

    @Test
    @DisplayName("participants 수동 섹션에 입력된 항목이 없거나 필드가 비어있으면 검증 실패")
    void participants_수동섹션_입력누락시_확정불가() {
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
                    "manualSections": [
                        {
                            "id": "m2",
                            "type": "participants",
                            "title": "참여자",
                            "data": {
                                "entries": [
                                    {
                                        "role": "책임기술자",
                                        "name": "",
                                        "qualification": "특급",
                                        "period": "2026"
                                    }
                                ]
                            }
                        }
                    ]
                }
                """;

        assertThatThrownBy(() -> validator.validate(contentJson))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING);
    }

    @Test
    @DisplayName("location-drawing-photos 수동 섹션에 사진이 없으면 검증 실패")
    void location_drawing_photos_사진누락시_확정불가() {
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
                    "manualSections": [
                        {
                            "id": "m3",
                            "type": "location-drawing-photos",
                            "title": "위치 사진",
                            "data": {
                                "images": []
                            }
                        }
                    ]
                }
                """;

        assertThatThrownBy(() -> validator.validate(contentJson))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING);
    }

    @Test
    @DisplayName("기타 수동 섹션에 body가 없거나 공백이면 검증 실패")
    void generic_manual_section_body_누락시_확정불가() {
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
                    "manualSections": [
                        {
                            "id": "m4",
                            "type": "custom",
                            "title": "특이사항",
                            "data": {
                                "body": "   "
                            }
                        }
                    ]
                }
                """;

        assertThatThrownBy(() -> validator.validate(contentJson))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING);
    }

    @Test
    @DisplayName("잘못된 JSON 형식 또는 빈 문자열인 경우 fail-closed로 거부")
    void 잘못된_JSON_fail_closed_거부() {
        assertThatThrownBy(() -> validator.validate(""))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING);

        assertThatThrownBy(() -> validator.validate("{invalid_json"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.REPORT_FINALIZATION_REQUIRED_FIELD_MISSING);
    }
}
