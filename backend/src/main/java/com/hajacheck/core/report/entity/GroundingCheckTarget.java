package com.hajacheck.core.report.entity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.global.exception.DomainValidationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** AI 응답과 실제 저장 payload 대조가 끝난 뒤 확정되는 보고서 grounding 대상. */
public record GroundingCheckTarget(
        String groundingRequestId, Long inspectionId, int reportVersion, String contentHash) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public GroundingCheckTarget {
        if (groundingRequestId == null || groundingRequestId.isBlank()) {
            throw new DomainValidationException("grounding 요청 ID는 필수다");
        }
        if (inspectionId == null) {
            throw new DomainValidationException("grounding 대상 점검 ID는 필수다");
        }
        if (reportVersion < 1) {
            throw new DomainValidationException("grounding 대상 보고서 버전은 1 이상이어야 한다");
        }
        if (contentHash == null || contentHash.isBlank()) {
            throw new DomainValidationException("grounding 대상 콘텐츠 해시는 필수다");
        }
    }

    public static GroundingCheckTarget capture(
            GroundingRequestContext context, String contentJson) {
        if (context == null) {
            throw new DomainValidationException("grounding 요청 context는 필수다");
        }
        return new GroundingCheckTarget(
                context.groundingRequestId(),
                context.inspectionId(),
                context.reportVersion(),
                hash(contentJson));
    }

    public boolean matches(Long inspectionId, int reportVersion, String contentJson) {
        return this.inspectionId.equals(inspectionId)
                && this.reportVersion == reportVersion
                && this.contentHash.equals(hash(contentJson));
    }

    private static String hash(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            throw new DomainValidationException("grounding 대상 보고서 본문 JSON은 필수다");
        }
        try {
            JsonNode content = MAPPER.readTree(contentJson);
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalize(content).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException e) {
            throw new DomainValidationException("grounding 대상 보고서 본문은 유효한 JSON이어야 한다");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }

    private static String canonicalize(JsonNode node) {
        if (node.isObject()) {
            List<String> fieldNames = new ArrayList<>();
            node.fieldNames().forEachRemaining(fieldNames::add);
            fieldNames.sort(Comparator.naturalOrder());
            return fieldNames.stream()
                    .map(name -> quote(name) + ":" + canonicalize(node.get(name)))
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (node.isArray()) {
            List<String> elements = new ArrayList<>();
            node.elements().forEachRemaining(element -> elements.add(canonicalize(element)));
            return String.join(",", elements).transform(value -> "[" + value + "]");
        }
        return node.toString();
    }

    private static String quote(String value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 필드명을 직렬화할 수 없습니다.", e);
        }
    }
}
