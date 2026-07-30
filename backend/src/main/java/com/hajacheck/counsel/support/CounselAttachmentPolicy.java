package com.hajacheck.counsel.support;

/**
 * 상담 채팅 이미지 첨부 정책 — 저장 카테고리, 저장키 검증, MIME 도출을 한 곳에 모은다. 업로드 서비스와
 * WS 메시지 서비스가 공유한다. 클라이언트가 보내는 MIME 은 신뢰하지 않고, 서버가 저장한 파일의 확장자
 * (LocalFileStorage 가 탐지 contentType 으로만 결정)로 MIME 을 재도출한다.
 */
public final class CounselAttachmentPolicy {

    /** FileStorageService 저장 하위 분류 — 다른 도메인(점검 미디어 등) 저장키를 상담에 붙이지 못하게 격리. */
    public static final String CATEGORY = "counsel-attachment";

    private CounselAttachmentPolicy() {
    }

    /** 저장키가 상담 첨부 카테고리 소속인지(다른 도메인 저장키 참조 차단). */
    public static boolean isCounselAttachmentKey(String storageKey) {
        return storageKey != null && storageKey.startsWith(CATEGORY + "/");
    }

    /** 저장키 확장자로 MIME 을 재도출한다(클라이언트 MIME 불신). 지원 외 확장자는 null. */
    public static String mimeTypeOf(String storageKey) {
        if (storageKey == null) {
            return null;
        }
        String lower = storageKey.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        return null;
    }
}
