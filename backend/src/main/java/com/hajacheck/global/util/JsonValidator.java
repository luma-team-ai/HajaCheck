package com.hajacheck.global.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hajacheck.global.exception.DomainValidationException;
import java.util.Map;
import java.util.Optional;

/**
 * jsonb 컬럼에 저장되는 String 값의 JSON 문법을 애플리케이션 경계에서 선검증한다.
 * PostgreSQL jsonb 타입도 쓰기 시점에 문법을 검증하지만 그 실패는 flush/commit 단계에서
 * 원시 SQL 예외로 표면화된다 — 여기서 먼저 걸러 명확한 {@link DomainValidationException}으로 대체한다.
 */
public final class JsonValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** {@link #mergeTextFields} 가 파손·비객체 원본을 잃지 않도록 담아두는 키. */
    private static final String PRESERVED_RAW_FIELD = "rawBeforeMerge";

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

    /**
     * 최상위 객체에 문자열 필드들을 <b>병합</b>해 새 JSON 문자열로 돌려준다 — 기존 키는 보존하고
     * 같은 이름의 키만 덮어쓴다(SQL 의 {@code jsonb || jsonb_build_object(...)} 와 같은 의미).
     *
     * <p><b>왜 필요한가</b>: {@code companies.business_registration_ocr_raw} 처럼 여러 주체가
     * (가입 서비스·마이그레이션·향후 OCR 연동) 각자 키를 써넣는 감사용 jsonb 는 <b>통째 교체가 곧
     * 감사 기록 소실</b>이다({@code Company} 클래스 javadoc 의 경고). 그래서 쓰기 경로는 항상 병합한다.
     *
     * <p><b>fail-safe 규약</b> — 이 메서드는 예외를 던지지 않는다.
     * <ul>
     *   <li>원본이 null·공백이면 새 객체에서 시작한다.</li>
     *   <li>원본이 파손됐거나 객체가 아니면(배열·스칼라) 병합할 구조가 없다 → 새 객체로 시작하되
     *       <b>원문을 {@code rawBeforeMerge} 키에 문자열로 보존</b>한다. 읽을 수 없다고 지워버리면
     *       그것도 감사 기록 소실이다(첫 병합의 결과는 항상 유효한 객체이므로 중첩되지 않는다).</li>
     *   <li>직렬화가 실패하면(문자열 값만 넣으므로 실질적으로 불가) 원본을 그대로 돌려준다 —
     *       새 키를 못 남길지언정 기존 기록을 파괴하지 않는 쪽을 택한다.</li>
     * </ul>
     *
     * @param fields 병합할 문자열 키·값(값이 null 인 항목은 무시한다 — JSON null 을 심지 않는다)
     * @return 병합 결과 JSON 문자열
     */
    public static String mergeTextFields(String json, Map<String, String> fields) {
        final ObjectNode root = mergeBase(json);
        fields.forEach((key, value) -> {
            if (value != null) {
                root.put(key, value);
            }
        });
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            return json;
        }
    }

    /** {@link #mergeTextFields} 의 병합 바탕 객체 — 원본이 객체면 그대로, 아니면 원문을 보존한 새 객체. */
    private static ObjectNode mergeBase(String json) {
        ObjectNode base = MAPPER.createObjectNode();
        if (json == null || json.isBlank()) {
            return base;
        }
        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            parsed = null;
        }
        if (parsed != null && parsed.isObject()) {
            return (ObjectNode) parsed;
        }
        base.put(PRESERVED_RAW_FIELD, json);
        return base;
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
