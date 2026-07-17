package com.hajacheck.core.ai.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * FastAPI {@code POST /ai/briefing} 성공 데이터 — {@code WeeklyBriefing}(openapi.yaml) 대응.
 *
 * <p>{@code facts} 하위 필드는 FastAPI 계약(snake_case)을 {@link JsonAlias} 로 읽어들이고,
 * 프론트 {@code AiBriefingFacts}(frontend/src/features/dashboard/types.ts)와 정합되도록
 * camelCase 필드명 그대로 직렬화해 반환한다 — {@code aiClient.ts} 에는 케이스 변환 인터셉터가
 * 없어(shared/api/aiClient.ts 참고) 이 응답 필드명이 곧 프론트 타입이 되기 때문이다.
 */
public record BriefingResponse(String briefing, String recommendation, BriefingFacts facts) {

    public record BriefingFacts(
            @JsonAlias("this_week_defects") Long thisWeekDefects,
            @JsonAlias("last_week_defects") Long lastWeekDefects,
            @JsonAlias("change_pct") Integer changePct,
            String trend,
            @JsonAlias("top_defect_type") String topDefectType,
            @JsonAlias("critical_defects") Long criticalDefects) {
    }
}
