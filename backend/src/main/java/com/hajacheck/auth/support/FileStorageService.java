package com.hajacheck.auth.support;

import org.springframework.web.multipart.MultipartFile;

/**
 * 파일 저장 추상화 — 회원가입 트랜잭션 밖(IO)에서 저장하고, 트랜잭션 실패 시 보상삭제한다.
 * 구현 교체(로컬 볼륨 → S3 등)를 위해 인터페이스로 분리한다.
 */
public interface FileStorageService {

    /**
     * 파일을 저장하고 접근 URL·저장키(보상삭제용)를 반환한다.
     * 검증 실패는 BusinessException(FILE_REQUIRED/FILE_INVALID_TYPE/FILE_TOO_LARGE),
     * IO 실패는 FILE_UPLOAD_FAILED 로 던진다.
     *
     * @param file     업로드 파일
     * @param category 저장 하위 분류(예: "business-registration")
     */
    StoredFile store(MultipartFile file, String category);

    /**
     * 저장키로 파일을 삭제한다(보상삭제, best-effort — 실패해도 예외를 던지지 않는다).
     */
    void delete(String storageKey);

    /**
     * 저장 결과 — url(조회용), storageKey(삭제/보상용).
     */
    record StoredFile(String url, String storageKey) {
    }
}
