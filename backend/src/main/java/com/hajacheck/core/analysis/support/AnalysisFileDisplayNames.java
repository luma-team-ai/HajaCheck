package com.hajacheck.core.analysis.support;

import com.hajacheck.core.media.entity.Media;

/**
 * AI 분석 실행/상태 화면(이미지별 처리 현황 테이블)에 표시할 파일명 — {@link Media#getOriginalFilename()}
 * (V26)이 있으면 그대로 쓰고, 없으면(V26 이전 업로드 행) 기존과 동일한 "이미지 N" 순번 라벨로 폴백한다.
 * InspectionAnalysisWorker/InspectionAnalysisService 양쪽이 동일 기준을 공유해야 하므로 이 클래스로 뺀다.
 */
public final class AnalysisFileDisplayNames {

    private AnalysisFileDisplayNames() {
    }

    /** @param index 0-base — 폴백 라벨은 1-base로 표시("이미지 1"부터). */
    public static String of(Media media, int index) {
        String originalFilename = media.getOriginalFilename();
        return originalFilename != null ? originalFilename : "이미지 " + (index + 1);
    }
}
