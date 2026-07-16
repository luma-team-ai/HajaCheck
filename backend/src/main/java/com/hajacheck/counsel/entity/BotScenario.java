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

/**
 * 버튼 선택 방식의 계층형 챗봇 시나리오 노드.
 *
 * <p>자기 참조 {@code parentId}는 FK 값 컬럼을 실제 매핑 소스로 두고, 같은 도메인 내부 지연 로딩
 * 연관관계({@code parent})는 조회 전용({@code insertable/updatable = false})으로 병행 제공한다.</p>
 */
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

    @Column(name = "parent_id")
    private Long parentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id", insertable = false, updatable = false)
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
    private BotScenario(Long parentId, String category, String buttonLabel,
                        String responseText, boolean leadsToCounselor, int sortOrder) {
        this.parentId = parentId;
        this.category = category;
        this.buttonLabel = buttonLabel;
        this.responseText = responseText;
        this.leadsToCounselor = leadsToCounselor;
        this.sortOrder = sortOrder;
    }

    public static BotScenario create(Long parentId, String category, String buttonLabel,
                                     String responseText, boolean leadsToCounselor, int sortOrder) {
        return BotScenario.builder()
                .parentId(parentId)
                .category(category)
                .buttonLabel(buttonLabel)
                .responseText(responseText)
                .leadsToCounselor(leadsToCounselor)
                .sortOrder(sortOrder)
                .build();
    }
}
