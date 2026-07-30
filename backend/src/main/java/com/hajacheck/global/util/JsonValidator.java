package com.hajacheck.global.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.global.exception.DomainValidationException;
import java.util.Optional;

/**
 * jsonb 컬럼에 저장되는 String 값의 JSON 문법을 애플리케이션 경계에서 선검증한다.
 * PostgreSQL jsonb 타입도 쓰기 시점에 문법을 검증하지만 그 실패는 flush/commit 단계에서
 * 원시 SQL 예외로 표면화된다 — 여기서 먼저 걸러 명확한 {@link DomainValidationException}으로 대체한다.
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
            throw new DomainValidationException(fieldName + ": 유효한 JSON이어야 한다");
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

    /**
     * 최상위 객체의 문자열 필드를 <b>절대 예외 없이</b> 읽는다 — 값이 없거나 JSON 이 깨졌으면
     * {@link Optional#empty()}. 조회 경로(응답 조립)에서 감사용 jsonb 를 들여다볼 때 쓴다.
     *
     * <p><b>왜 fail-safe 인가</b>: 이 컬럼들은 사람이·마이그레이션이·외부 연동이 각각 써넣는 자유
     * 스키마 영역이라 언제든 예상 밖 모양일 수 있다. 그걸 읽다가 예외가 나면 아무 상관 없는 조회 API
     * (예: 마이페이지)가 통째로 500 이 된다. "못 읽었다"는 호출부가 도메인 규칙으로 해석할 문제다.
     *
     * @return 해당 키의 <b>문자열</b> 값. 키 없음·null·비문자열(객체/배열/숫자)·문법 오류는 모두 empty.
     */
    public static Optional<String> readTextField(String json, String fieldName) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        JsonNode value = root.get(fieldName);
        return value != null && value.isTextual() ? Optional.of(value.asText()) : Optional.empty();
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
