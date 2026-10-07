package com.mongle.backend.domain.dream.image;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.global.error.BusinessException;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import javax.imageio.ImageIO;

class ImagePayloadTest {
    static byte[] png() {
        try {
            var output = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "png", output);
            return output.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void readsActualMimeDimensionsAndCopiesBytes() {
        var raw = png();
        var payload = ImagePayload.parse(raw);
        assertThat(payload.contentType()).isEqualTo("image/png");
        assertThat(payload.width()).isEqualTo(2);
        assertThat(payload.height()).isEqualTo(3);
        raw[0] = 0;
        var copy = payload.bytes();
        copy[0] = 0;
        assertThat(payload.bytes()[0]).isEqualTo((byte) 137);
    }

    @Test
    void supportsActualJpeg() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "jpeg", output);
        assertThat(ImagePayload.parse(output.toByteArray()).contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void rejectsEmptyOversizedTruncatedAndNonImageData() {
        for (var raw :
                List.of(
                        new byte[0],
                        new byte[ImagePayload.MAX_BYTES + 1],
                        "<svg>image</svg>".getBytes(),
                        new byte[] {(byte) 255, (byte) 216, (byte) 255},
                        java.util.Arrays.copyOf(png(), 20))) {
            assertThatThrownBy(() -> ImagePayload.parse(raw)).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> ImagePayload.parse(null)).isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsUnsafeDimensionsBeforeFullDecode() {
        var raw = png();
        java.nio.ByteBuffer.wrap(raw).putInt(16, 100_000);
        assertThatThrownBy(() -> ImagePayload.parse(raw)).isInstanceOf(BusinessException.class);
    }

    @Test
    void validatesOnlyConfiguredOptionsAndDoesNotInventPmChoices() {
        var empty = new ImageOptions(null, null);
        assertThat(empty.styles()).isEmpty();
        assertThatThrownBy(() -> empty.requireAllowed("test-style", null))
                .isInstanceOf(BusinessException.class);
        var options = new ImageOptions(List.of("test-style"), List.of("test-mood"));
        options.requireAllowed("test-style", null);
        options.requireAllowed("test-style", "test-mood");
        assertThatThrownBy(() -> options.requireAllowed("TEST-STYLE", null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> options.requireAllowed("test-style", "unknown"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new ImageOptions(List.of("a", "a"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImageOptions(List.of("bad key"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
