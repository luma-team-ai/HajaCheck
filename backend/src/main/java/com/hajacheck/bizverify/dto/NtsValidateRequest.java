package com.hajacheck.bizverify.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 국세청 진위확인 요청 바디(#596) — {@code POST /api/nts-businessman/v1/validate}.
 * <pre>
 * {"businesses":[{"b_no":"숫자10","start_dt":"YYYYMMDD","p_nm":"대표자명"}]}
 * </pre>
 * 한 건만 조회하므로 businesses 는 항상 원소 1개 리스트다.
 */
public record NtsValidateRequest(List<Business> businesses) {

    public record Business(
            @JsonProperty("b_no") String bNo,
            @JsonProperty("start_dt") String startDt,
            @JsonProperty("p_nm") String pNm) {
    }
}
