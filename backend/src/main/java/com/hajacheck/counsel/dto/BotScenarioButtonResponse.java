package com.hajacheck.counsel.dto;

import com.hajacheck.counsel.entity.BotScenario;

/** 시나리오 버튼(노드) 요약 — 목록·자식 버튼 렌더링용. */
public record BotScenarioButtonResponse(
        Long id,
        String category,
        String buttonLabel,
        boolean leadsToCounselor) {

    public static BotScenarioButtonResponse from(BotScenario scenario) {
        return new BotScenarioButtonResponse(
                scenario.getId(),
                scenario.getCategory(),
                scenario.getButtonLabel(),
                scenario.isLeadsToCounselor());
    }
}
