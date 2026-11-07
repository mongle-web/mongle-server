package com.mongle.backend.domain.legal;

import com.mongle.backend.global.error.BusinessException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** No personal information. One immutable content digest per document version. */
@Entity
@Getter
@Table(name = "legal_document_versions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LegalDocument {
    @Id @Column(length = 64) private String id;
    @Column(name = "document_type", nullable = false, length = 20) private String documentType;
    @Column(name = "document_version", nullable = false, length = 40) private String documentVersion;
    @Column(name = "document_digest", length = 64) private String documentDigest;

    public static String key(String type, String version) { return type + ":" + version; }

    void bindDigest(String digest) {
        if (documentDigest != null && !documentDigest.equals(digest)) {
            throw new BusinessException(LegalError.DOCUMENT_CONFLICT);
        }
        documentDigest = digest;
    }
}
