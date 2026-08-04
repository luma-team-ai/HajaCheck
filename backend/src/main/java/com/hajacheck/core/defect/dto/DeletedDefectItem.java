package com.hajacheck.core.defect.dto;

import java.time.LocalDateTime;

/**
 * 오탐 삭제된 하자 1건(#1399) — 결과 뷰어의 "삭제된 하자" 접이식 목록·되살리기용.
 *
 * <p>{@link DefectDetailItem}을 감싸 하자 본문을 그대로 재사용하고, 삭제 이력
 * ({@code defect_revisions}, field_changed='is_deleted')에서 온 3필드만 덧붙인다 —
 * 검수자가 "무엇을 왜 언제 누가 지웠는지" 보고 되살릴지 판단하는 데 필요한 최소 집합이다.
 *
 * <p>사유는 삭제 시점에 1~500자 필수로 입력받아 저장돼 있었으나 어느 화면에서도 읽을 수
 * 없었다(모든 조회가 is_deleted=false 필터) — PRD FR-4가 요구하는 "감사용 defect_revisions를
 * 화면에 노출"에서 오탐 삭제분만 빠져 있던 것을 메운다.
 *
 * <p>{@code deletedByName}은 이력 시점의 사용자 이름이 아니라 현재 이름이다(사용자가 개명하면
 * 과거 기록의 표시도 바뀐다) — 이름 스냅샷을 이력에 남기지 않는 기존 방식을 따른다.
 * 탈퇴 등으로 사용자를 찾을 수 없으면 null.
 */
public record DeletedDefectItem(
        DefectDetailItem defect,
        String deletedReason,
        LocalDateTime deletedAt,
        String deletedByName
) {
}
