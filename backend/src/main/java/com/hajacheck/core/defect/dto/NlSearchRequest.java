package com.hajacheck.core.defect.dto;

/**
 * 하자 자연어 검색 요청 — openapi.yaml NlSearchRequest(HAJA-120/179~183).
 * trim 후 1~500자 검증은 NlSearchService에서 수행한다(빈 질의/공백만/500자 초과를 INVALID_INPUT으로
 * 통일하기 위해 Bean Validation 대신 서비스 계층에서 명시적으로 처리 — trim 이후 길이만 검증 대상).
 */
public record NlSearchRequest(String query) {
}
