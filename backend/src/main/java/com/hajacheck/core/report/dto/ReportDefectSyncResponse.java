package com.hajacheck.core.report.dto;

/**
 * grounding-recheck·resync-defects 공통 응답(#1653 P2) — 보고서 상세와 함께, 본문(detail.items)이
 * 현재 확정 하자 목록과 어떻게 다른지(diff)를 실어 보낸다. grounding-recheck는 진단만 하고 본문을
 * 바꾸지 않으므로 diff가 "지금 resync하면 이렇게 바뀐다"를 미리 보여주는 역할이고, resync-defects는
 * 실제로 그 diff를 적용한 뒤의 결과를 같은 모양으로 반환한다.
 */
public record ReportDefectSyncResponse(ReportDetailResponse report, ReportDefectDiffResponse diff) {
}
