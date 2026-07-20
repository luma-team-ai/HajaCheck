package com.hajacheck.core.report.support;

import org.springframework.web.multipart.MultipartFile;

/**
 * 보고서 PDF 저장 추상화(#446) — auth.support.FileStorageService 와 같은 로컬 볼륨 저장 패턴을 따르되,
 * report 도메인이 auth 도메인 구현을 직접 참조하지 않도록 별도로 둔다(도메인 결합 방지, handoff 지시).
 */
public interface ReportPdfStorage {

    /**
     * PDF 파일을 저장하고 접근 URL을 반환한다.
     * 검증 실패는 BusinessException(FILE_REQUIRED/FILE_INVALID_TYPE/FILE_TOO_LARGE),
     * IO 실패는 FILE_UPLOAD_FAILED 로 던진다.
     */
    String store(MultipartFile file);
}
