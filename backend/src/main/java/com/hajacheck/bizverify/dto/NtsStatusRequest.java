package com.hajacheck.bizverify.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 국세청 사업자등록상태조회 요청 바디(#648) — {@code POST /api/nts-businessman/v1/status}.
 * <pre>
 * {"b_no":["숫자10"]}
 * </pre>
 * 한 건만 조회하므로 b_no 는 항상 원소 1개 리스트다.
 */
public record NtsStatusRequest(@JsonProperty("b_no") List<String> bNo) {
}
