package com.hajacheck.core.report.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GroundingCheckTargetTest {

    /**
     * HAJA-397: ai-server의
     * test_canonical_content_hash_golden_cross_language_verification(test_report.py:1093)과
     * 동일한 payload를 사용한다. 두 테스트의 고정 해시값이 하나라도 어긋나면 Python
     * _canonical_content_hash와 Java hash()의 정규화 로직이 실제로 갈라졌다는 뜻이다.
     */
    @Test
    void contentHashMatchesPythonCanonicalGoldenValue() {
        String contentJson =
                "{"
                        + "\"overview\":{\"purpose\":\"정기점검\",\"facility_summary\":\"강남빌딩\",\"scope\":\"외벽\"},"
                        + "\"summary\":{"
                        + "\"overall_opinion\":\"보수 필요\","
                        + "\"total_count\":1,"
                        + "\"count_by_grade\":{\"A\":0,\"B\":1,\"C\":0,\"D\":0,\"E\":0},"
                        + "\"key_findings\":[\"균열 1건\"]"
                        + "},"
                        + "\"detail\":{\"items\":[{"
                        + "\"defect_type\":\"CRACK\","
                        + "\"location\":\"외벽 우측\","
                        + "\"severity_grade\":\"B\","
                        + "\"description\":\"세로 균열\","
                        + "\"cause\":\"건조수축\""
                        + "}]},"
                        + "\"recommendation\":{"
                        + "\"items\":[{"
                        + "\"target\":\"CRACK\","
                        + "\"method\":\"에폭시 주입\","
                        + "\"priority\":\"HIGH\","
                        + "\"legal_basis\":\"관련 근거 없음\","
                        + "\"legal_basis_verified\":false"
                        + "}],"
                        + "\"monitoring_points\":[\"균열 부위\"]"
                        + "},"
                        + "\"grounding_ok\":true"
                        + "}";

        GroundingRequestContext context = new GroundingRequestContext("REQ-GOLDEN-001", 1L, 1);
        GroundingCheckTarget target = GroundingCheckTarget.capture(context, contentJson);

        assertThat(target.contentHash())
                .isEqualTo("629f35f1e9aae5437d143cd2edc3e304a57dfefa888e75ae255ebb397f8f6323");
    }
}
