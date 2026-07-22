package com.hajacheck.core.media.support;

import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.web.multipart.MultipartFile;

/**
 * 매직바이트(파일 시그니처) 검증(PRD FR-2 "업로드 보안 검증") — 클라이언트가 보낸 Content-Type 헤더는
 * 조작 가능하므로 신뢰하지 않고, 파일의 실제 앞부분 바이트가 선언된 타입의 진짜 시그니처와 일치하는지
 * 직접 대조한다. 이번 PR 범위(이미지 2종)에 한해 Tika 같은 무거운 의존성 없이 수동 대조로 충분하다.
 */
public final class ImageSignatureValidator {

    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private ImageSignatureValidator() {
    }

    /**
     * 선언된 contentType과 실제 파일 시그니처가 일치하는지 검증한다. 불일치하면 FILE_INVALID_TYPE.
     */
    public static void validate(MultipartFile file) {
        String declaredType = file.getContentType();
        byte[] header = readHeader(file, PNG_MAGIC.length);

        boolean matches = "image/jpeg".equals(declaredType) && startsWith(header, JPEG_MAGIC)
                || "image/png".equals(declaredType) && startsWith(header, PNG_MAGIC);
        if (!matches) {
            throw new BusinessException(ErrorCode.FILE_INVALID_TYPE);
        }
    }

    private static byte[] readHeader(MultipartFile file, int length) {
        try (InputStream in = file.getInputStream()) {
            byte[] header = in.readNBytes(length);
            if (header.length < length) {
                throw new BusinessException(ErrorCode.FILE_INVALID_TYPE);
            }
            return header;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
