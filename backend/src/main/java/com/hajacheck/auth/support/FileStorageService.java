package com.hajacheck.auth.support;

import java.util.Collection;
import org.springframework.web.multipart.MultipartFile;

/**
 * 파일 저장 추상화 — 회원가입 트랜잭션 밖(IO)에서 저장하고, 트랜잭션 실패 시 보상삭제한다.
 * 구현 교체(로컬 볼륨 → S3 등)를 위해 인터페이스로 분리한다.
 *
 * <p>허용 MIME/최대 용량은 호출부에서 명시 전달한다(도메인마다 기준이 다름 — 예: 사업자등록증은
 * JPG/PNG/PDF·10MB, 점검 미디어는 JPG/PNG·더 큰 용량). 전역 기본값에 암묵 의존하지 않는다.
 */
public interface FileStorageService {

    /**
     * 업로드 파일을 저장하고 접근 URL·저장키(보상삭제용)를 반환한다.
     * 검증 실패는 BusinessException(FILE_REQUIRED/FILE_INVALID_TYPE/FILE_TOO_LARGE),
     * IO 실패는 FILE_UPLOAD_FAILED 로 던진다.
     *
     * @param file                업로드 파일
     * @param category            저장 하위 분류(예: "business-registration")
     * @param allowedContentTypes 허용 MIME 화이트리스트
     * @param maxSizeBytes        허용 최대 용량(bytes)
     */
    StoredFile store(MultipartFile file, String category, Collection<String> allowedContentTypes, long maxSizeBytes);

    /**
     * 서버가 생성한 바이트(예: 썸네일 재인코딩 결과)를 저장한다 — 실제 업로드가 아니므로 {@link MultipartFile}이
     * 아닌 바이트 배열을 받는다. 검증/예외 규칙은 {@link #store} 와 동일.
     */
    StoredFile storeBytes(byte[] content, String contentType, String category,
                           Collection<String> allowedContentTypes, long maxSizeBytes);

    /**
     * 저장키로 파일을 삭제한다(보상삭제, best-effort — 실패해도 예외를 던지지 않는다).
     */
    void delete(String storageKey);

    /**
     * 저장키로 파일 바이트를 읽는다(인가된 다운로드/서빙 엔드포인트용). 미존재/IO 실패는 FILE_UPLOAD_FAILED.
     */
    byte[] read(String storageKey);

    /**
     * 저장 결과 — url(조회용), storageKey(삭제/보상용).
     */
    record StoredFile(String url, String storageKey) {
    }
}
