package com.mongle.backend.domain.dream.image;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mongle.backend.global.error.BusinessException;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Map;

class ImageControllerTest {
    @Test
    void signingFailureDoesNotPassPrivateUrlToGlobalExceptionLogger() {
        var service = mock(DreamImageService.class);
        var controller =
                new ImageController(
                        service, mock(ImageTransactions.class), new ImageOptions(null, null));
        var jwt =
                new Jwt(
                        "token",
                        Instant.now(),
                        Instant.now().plusSeconds(60),
                        Map.of("alg", "none"),
                        Map.of("sub", "1"));
        var internal =
                new BusinessException(
                        ImageErrorCode.CALL_FAILED,
                        new IllegalStateException("https://images.test/private?signature=secret"));
        when(service.downloadUrl(1L, 2L)).thenThrow(internal);
        assertThatThrownBy(() -> controller.downloadUrl(jwt, 2L))
                .isInstanceOf(BusinessException.class)
                .hasNoCause()
                .hasMessage(ImageErrorCode.CALL_FAILED.getMessage());
        assertThat(internal.getCause()).hasMessageContaining("signature=secret");
    }

    @Test
    void generationRecoveryFailureDoesNotPassProviderResponseToGlobalLogger() {
        var service = mock(DreamImageService.class);
        var controller =
                new ImageController(
                        service, mock(ImageTransactions.class), new ImageOptions(null, null));
        var jwt =
                new Jwt(
                        "token",
                        Instant.now(),
                        Instant.now().plusSeconds(60),
                        Map.of("alg", "none"),
                        Map.of("sub", "1"));
        var request = new ImageRequest(0L, "test-style", null, false, null);
        when(service.generate(1L, 2L, request))
                .thenThrow(
                        new BusinessException(
                                ImageErrorCode.CALL_FAILED,
                                new IllegalStateException("private provider response")));
        assertThatThrownBy(() -> controller.generate(jwt, 2L, request))
                .isInstanceOf(BusinessException.class)
                .hasNoCause()
                .hasMessage(ImageErrorCode.CALL_FAILED.getMessage());
    }
}
