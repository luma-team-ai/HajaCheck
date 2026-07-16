package com.hajacheck.core.media.support;

import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * 업로드 원본을 비율 유지 축소 후 JPEG로 재인코딩한 썸네일 바이트를 생성한다(PRD FR-2
 * "업로드 완료 시 썸네일 생성"). 표준 javax.imageio 만 사용 — 별도 라이브러리 의존성 불필요.
 * 원본이 무엇이든(JPEG/PNG) 썸네일은 항상 JPEG 로 통일해, 서빙 시 Content-Type 분기가 필요 없게 한다.
 */
public final class ImageThumbnailGenerator {

    private ImageThumbnailGenerator() {
    }

    public static byte[] generate(byte[] originalBytes, int maxDimension) {
        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(originalBytes));
            if (original == null) {
                throw new BusinessException(ErrorCode.FILE_INVALID_TYPE);
            }

            int width = original.getWidth();
            int height = original.getHeight();
            double scale = Math.min(1.0, (double) maxDimension / Math.max(width, height));
            int targetWidth = Math.max(1, (int) Math.round(width * scale));
            int targetHeight = Math.max(1, (int) Math.round(height * scale));

            BufferedImage resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resized.createGraphics();
            try {
                // PNG 투명 배경을 흰색으로 채워 재인코딩(JPEG는 알파 채널 미지원 — 채우지 않으면 검게 렌더링됨).
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, targetWidth, targetHeight);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(original, 0, 0, targetWidth, targetHeight, null);
            } finally {
                g.dispose();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(resized, "jpg", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }
    }
}
