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
        // null뿐 아니라 공백까지 폴백 대상으로 본다(PR머신 P3). MultipartFile#getOriginalFilename()은
        // 계약상 빈 문자열을 반환할 수 있어 ""가 그대로 저장될 수 있는데, null만 검사하면 폴백이 발동하지
        // 않아 파일명 셀이 빈칸으로 표시된다 — "식별 가능한 파일명을 보여준다"는 이 기능의 목적 자체가
        // 무력화된다. 저장 단계(MediaService)에서도 공백을 null로 정규화하지만, V26 이전에 이미 저장된
        // 행까지 함께 방어하려면 표시 시점에도 같은 기준이 필요하다.
        return originalFilename != null && !originalFilename.isBlank()
                ? originalFilename
                : "이미지 " + (index + 1);
    }
}
