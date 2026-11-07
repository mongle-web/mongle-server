package com.mongle.backend.domain.legal;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface LegalDocumentRepository extends JpaRepository<LegalDocument, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from LegalDocument d where d.id=:id")
    Optional<LegalDocument> lock(@Param("id") String id);
}
