package com.mongle.backend.domain.dream.image;

import com.mongle.backend.global.error.BusinessException;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** 다운로드나 URL 접근 없이 실제 이미지 바이트를 검증한다. PNG/JPEG만 지원한다. */
public record ImagePayload(byte[] bytes, String contentType, int width, int height) {
    public static final int MAX_BYTES = 10 * 1024 * 1024;

    public ImagePayload {
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public String toString() {
        return "ImagePayload[type=" + contentType + ",width=" + width + ",height=" + height + "]";
    }

    public static ImagePayload parse(byte[] data) {
        if (data == null || data.length == 0 || data.length > MAX_BYTES) throw invalid();
        // MIME과 파일 이름은 외부 응답을 신뢰하지 않고 실제 헤더·디코딩 결과에서 결정한다.
        boolean png =
                data.length >= 8
                        && data[0] == (byte) 137
                        && data[1] == 80
                        && data[2] == 78
                        && data[3] == 71
                        && data[4] == 13
                        && data[5] == 10
                        && data[6] == 26
                        && data[7] == 10;
        boolean jpeg =
                data.length >= 3
                        && data[0] == (byte) 255
                        && data[1] == (byte) 216
                        && data[2] == (byte) 255;
        if (!png && !jpeg) throw invalid();
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw invalid();
            var reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1
                        || height < 1
                        || width > 8192
                        || height > 8192
                        || (long) width * height > 16_777_216L) throw invalid();
                if (reader.read(0) == null) throw invalid();
                return new ImagePayload(data, png ? "image/png" : "image/jpeg", width, height);
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException ex) {
            throw new BusinessException(ImageErrorCode.INVALID_OUTPUT, ex);
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(ImageErrorCode.INVALID_OUTPUT);
    }
}
