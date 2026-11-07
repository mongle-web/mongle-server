package com.mongle.backend.domain.legal;
import org.springframework.data.jpa.repository.JpaRepository;
public interface LegalConsentRepository extends JpaRepository<LegalConsent,Long> {
    boolean existsByUserIdAndDocumentTypeAndDocumentVersion(Long userId,String documentType,String documentVersion);
}
