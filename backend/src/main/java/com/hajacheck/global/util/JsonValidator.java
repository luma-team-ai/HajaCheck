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

    /**
     * null·공백 문자열은 "값 없음"으로 허용한다(선택 컬럼의 미설정 상태).
     * ⚠️ Jackson {@code readTree("")}는 예외를 던지지 않고 MissingNode를 반환하므로, 공백을 여기서
     * 걸러주지 않으면 검증을 그대로 통과한 뒤 PostgreSQL jsonb가 빈 문자열을 거부해 flush 시점
     * 원시 SQL 예외로 샌다 — 이 메서드가 막으려던 실패 모드가 그대로 재현되는 것을 방지한다.
     */
    public static void requireValidJson(String json, String fieldName) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(fieldName + "는 유효한 JSON이어야 한다: " + e.getOriginalMessage());
        }
    }

    /**
     * null·공백 문자열을 실제 null로 정규화해 반환한다(빈 문자열이 그대로 jsonb 컬럼에 저장되어
     * DB 레벨에서 거부되는 것을 방지). 값이 있으면 {@link #requireValidJson}으로 검증한 뒤 그대로 반환한다.
     */
    public static String normalizeOrRequireValid(String json, String fieldName) {
        if (json == null || json.isBlank()) {
            return null;
        }
        requireValidJson(json, fieldName);
        return json;
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
