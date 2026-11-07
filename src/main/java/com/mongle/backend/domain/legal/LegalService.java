package com.mongle.backend.domain.legal;

import com.mongle.backend.domain.user.dto.UserResponse;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.exception.UserErrorCode;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.error.BusinessException;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(LegalProperties.class)
public class LegalService {
    // Content changes require new versions, registry rows, hashes and matching frontend documents.
    public static final String TERMS_VERSION = "2026-11-07-v1";
    public static final String PRIVACY_VERSION = "2026-11-07-v1";

    private final LegalProperties properties;
    private final LegalDocumentRepository documents;
    private final LegalConsentRepository consents;
    private final UserRepository users;
    private final Clock authClock;

    @Schema(name = "LegalDocumentMetadata")
    public record Document(String type, String version, String digest, boolean required) {}

    @Schema(name = "LegalDocumentCatalog")
    public record Catalog(boolean published, List<Document> documents) {}

    @Schema(name = "LegalConsentRequest")
    public record Accept(
            @Schema(requiredMode = REQUIRED, example = "2026-11-07-v1") String termsVersion,
            @Schema(requiredMode = REQUIRED, pattern = "[0-9a-f]{64}") String termsDigest,
            @Schema(requiredMode = REQUIRED, description = "이용약관에 실제 동의한 경우만 true") Boolean termsAccepted,
            @Schema(requiredMode = REQUIRED, example = "2026-11-07-v1") String privacyVersion,
            @Schema(requiredMode = REQUIRED, pattern = "[0-9a-f]{64}") String privacyDigest,
            @Schema(requiredMode = REQUIRED, description = "필수 개인정보 수집·이용 고지에 실제 동의한 경우만 true") Boolean privacyAccepted,
            @Schema(requiredMode = REQUIRED, description = "만14세 이상임을 실제 확인한 경우만 true. 생년월일을 수집하지 않는 자기 확인입니다.") Boolean age14OrOlder) {}

    @Transactional(readOnly = true)
    public Catalog catalog() {
        if (properties.published()) {
            verifyDocument("TERMS", TERMS_VERSION, properties.termsDigest());
            verifyDocument("PRIVACY", PRIVACY_VERSION, properties.privacyDigest());
        }
        return new Catalog(properties.published(), List.of(
                new Document("TERMS", TERMS_VERSION, properties.termsDigest(), true),
                new Document("PRIVACY", PRIVACY_VERSION, properties.privacyDigest(), true)));
    }

    private void verifyDocument(String type, String version, String digest) {
        var document = documents.findById(LegalDocument.key(type, version))
                .orElseThrow(() -> new BusinessException(LegalError.UNAVAILABLE));
        if (document.getDocumentDigest() != null && !document.getDocumentDigest().equals(digest)) {
            throw new BusinessException(LegalError.DOCUMENT_CONFLICT);
        }
    }

    public boolean current(User user) {
        return !user.isWithdrawn() && properties.published()
                && TERMS_VERSION.equals(user.getTermsVersion())
                && PRIVACY_VERSION.equals(user.getPrivacyVersion())
                && properties.termsDigest().equals(user.getTermsDigest())
                && properties.privacyDigest().equals(user.getPrivacyDigest());
    }

    public UserResponse response(User user) {
        return UserResponse.from(user, current(user));
    }

    public boolean accessAllowed(User user) {
        return !properties.enforce() || current(user);
    }

    public void requireAccepted(User user) {
        if (properties.enforce() && !current(user)) {
            throw new BusinessException(LegalError.REQUIRED);
        }
    }

    @Transactional
    public UserResponse accept(Long id, Accept request) {
        requireValidRequest(request);
        // Keep the user-first order used by onboarding and withdrawal, then lock
        // global document rows in a fixed order across members and instances.
        var user = users.findByIdForUpdate(id).filter(account -> !account.isWithdrawn())
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        bindDocument("TERMS", TERMS_VERSION, properties.termsDigest());
        bindDocument("PRIVACY", PRIVACY_VERSION, properties.privacyDigest());
        var at = LocalDateTime.ofInstant(authClock.instant(), ZoneOffset.UTC);
        saveOnce(id, "TERMS", TERMS_VERSION, properties.termsDigest(), at);
        saveOnce(id, "PRIVACY", PRIVACY_VERSION, properties.privacyDigest(), at);
        user.acceptLegal(TERMS_VERSION, properties.termsDigest(), PRIVACY_VERSION, properties.privacyDigest());
        consents.flush();
        return response(user);
    }

    private void requireValidRequest(Accept request) {
        if (!properties.published()) {
            throw new BusinessException(LegalError.UNAVAILABLE);
        }
        if (request == null || !Boolean.TRUE.equals(request.termsAccepted())
                || !Boolean.TRUE.equals(request.privacyAccepted()) || !Boolean.TRUE.equals(request.age14OrOlder())) {
            throw new BusinessException(LegalError.INVALID);
        }
        if (!TERMS_VERSION.equals(request.termsVersion()) || !PRIVACY_VERSION.equals(request.privacyVersion())
                || !properties.termsDigest().equals(request.termsDigest())
                || !properties.privacyDigest().equals(request.privacyDigest())) {
            throw new BusinessException(LegalError.VERSION);
        }
    }

    private void bindDocument(String type, String version, String digest) {
        documents.lock(LegalDocument.key(type, version))
                .orElseThrow(() -> new BusinessException(LegalError.UNAVAILABLE)).bindDigest(digest);
    }

    private void saveOnce(Long user, String type, String version, String digest, LocalDateTime at) {
        if (!consents.existsByUserIdAndDocumentTypeAndDocumentVersion(user, type, version)) {
            consents.save(LegalConsent.create(user, type, version, digest, at));
        }
    }
}
