package com.hajacheck.core.report.support;

import java.time.Instant;
import java.util.List;
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

    /**
     * reportId 하위에 저장된 모든 PDF 파일과 디렉터리를 삭제한다(#1653 P3 — 고아 PDF).
     * DRAFT 보고서 soft delete 시 호출해, pdfUrl로 참조된 적 없더라도(finalize 전 업로드 후 방치된 경우
     * 포함) 그 보고서 소유의 저장 파일을 즉시 정리한다. 대상이 없으면 아무 일도 하지 않는다.
     */
    void deleteAll(Long reportId);

    /**
     * 저장소에 파일이 남아 있는 reportId 전체를 나열한다(#1653 P3 정리 배치용) — 순서 무보장.
     */
    List<Long> listReportIdsWithStoredFiles();

    /**
     * reportId 하위 파일 중 {@code keepStorageKey}(null이면 전부 후보)를 제외하고, 수정시각이
     * {@code olderThan}보다 이전인 파일만 삭제한다(#1653 P3 정리 배치 — 미참조+N일 경과만 삭제).
     * FINALIZED 보고서의 확정 PDF(keepStorageKey)는 항상 보존하고, 그 외 방치된(finalize 전 업로드
     * 후 확정하지 않은) 파일만 유예기간이 지난 뒤 제거한다. 삭제한 파일 수를 반환한다.
     */
    int deleteOrphans(Long reportId, String keepStorageKey, Instant olderThan);
}
