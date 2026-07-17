package com.hajacheck.global.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * jsonb 컬럼에 저장되는 String 값의 JSON 문법을 애플리케이션 경계에서 선검증한다.
 * PostgreSQL jsonb 타입도 쓰기 시점에 문법을 검증하지만 그 실패는 flush/commit 단계에서
 * 원시 SQL 예외로 표면화된다 — 여기서 먼저 걸러 명확한 {@link IllegalArgumentException}으로 대체한다.
 */
public final class JsonValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonValidator() {
    }

    /** null은 "값 없음"으로 허용한다(선택 컬럼의 미설정 상태). */
    public static void requireValidJson(String json, String fieldName) {
        if (json == null) {
            return;
        }
        try {
            MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(fieldName + "는 유효한 JSON이어야 한다: " + e.getOriginalMessage());
        }
    }

    /** null·빈 배열([])·빈 객체({})·JSON null 리터럴을 모두 "내용 없음"으로 취급한다. */
    public static boolean isEmptyJson(String json) {
        if (json == null || json.isBlank()) {
            return true;
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            return false;
        }
        if (node.isNull() || node.isMissingNode()) {
            return true;
        }
        if (node.isArray() || node.isObject()) {
            return node.isEmpty();
        }
        return false;
    }
}
