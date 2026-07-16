package com.hajacheck.counsel.entity;

import com.hajacheck.global.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 버튼 선택 방식의 계층형 챗봇 시나리오 노드. */
@Entity
@Getter
@Table(name = "bot_scenarios", indexes = {
        @Index(name = "idx_bot_scenarios_parent", columnList = "parent_id")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BotScenario extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private BotScenario parent;

    @Column(nullable = false, length = 100)
    private String category;

    @Column(name = "button_label", nullable = false, length = 200)
    private String buttonLabel;

    @Column(name = "response_text", columnDefinition = "text")
    private String responseText;

    @Column(name = "leads_to_counselor", nullable = false)
    private boolean leadsToCounselor;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Builder(access = AccessLevel.PRIVATE)
    private BotScenario(BotScenario parent, String category, String buttonLabel,
                        String responseText, boolean leadsToCounselor, int sortOrder) {
        this.parent = parent;
        this.category = category;
        this.buttonLabel = buttonLabel;
        this.responseText = responseText;
        this.leadsToCounselor = leadsToCounselor;
        this.sortOrder = sortOrder;
    }

    public static BotScenario create(BotScenario parent, String category, String buttonLabel,
                                     String responseText, boolean leadsToCounselor, int sortOrder) {
        return BotScenario.builder()
                .parent(parent)
                .category(category)
                .buttonLabel(buttonLabel)
                .responseText(responseText)
                .leadsToCounselor(leadsToCounselor)
                .sortOrder(sortOrder)
                .build();
    }
}
