package com.hajacheck.core.report.support;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

/**
 * 보고서 PDF 저장 추상화(#446) — auth.support.FileStorageService 와 같은 로컬 볼륨 저장 패턴을 따르되,
 * report 도메인이 auth 도메인 구현을 직접 참조하지 않도록 별도로 둔다(도메인 결합 방지, handoff 지시).
 *
 * <p>보고서 PDF 는 시설물 하자 정보를 담은 민감문서라 정적 리소스 핸들러로 직접 서빙하지 않고,
 * 소유권 검증(IDOR 방지)을 거친 컨트롤러 다운로드 엔드포인트가 {@link #load(String)} 로만 읽어간다(#455 P2-1).
 */
public interface ReportPdfStorage {

    /**
     * PDF 파일을 지정된 reportId 하위에 저장하고 저장 식별자(storageKey — 단일 경로 세그먼트)를 반환한다.
     * 검증 실패는 BusinessException(FILE_REQUIRED/FILE_INVALID_TYPE/FILE_TOO_LARGE),
     * IO 실패는 FILE_UPLOAD_FAILED 로 던진다.
     */
    String store(Long reportId, MultipartFile file);

    /**
     * reportId 하위의 storageKey 에 해당하는 저장 파일을 Resource 로 로드한다.
     * storageKey 가 경로 트래버설을 시도하거나 파일이 존재하지 않으면 FILE_NOT_FOUND 로 던진다
     * (존재 여부 열거 방지를 위해 트래버설/미존재 모두 동일 응답).
     */
    Resource load(Long reportId, String storageKey);
}
