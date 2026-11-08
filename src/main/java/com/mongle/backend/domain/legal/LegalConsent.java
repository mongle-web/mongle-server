package com.mongle.backend.domain.legal;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
@Entity @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
@Table(name="legal_consents",uniqueConstraints=@UniqueConstraint(name="uk_legal_consent_version",columnNames={"user_id","document_type","document_version"}))
public class LegalConsent {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="user_id",nullable=false) private Long userId;
    @Column(name="document_type",nullable=false,length=20) private String documentType;
    @Column(name="document_version",nullable=false,length=40) private String documentVersion;
    @Column(name="document_digest",nullable=false,length=64) private String documentDigest;
    @Column(name="accepted_at",nullable=false) private LocalDateTime acceptedAt;
    @Column(name="age14_confirmed",nullable=false) private boolean age14Confirmed;
    static LegalConsent create(Long user,String type,String version,String digest,LocalDateTime at) {
        var c=new LegalConsent(); c.userId=user;c.documentType=type;c.documentVersion=version;c.documentDigest=digest;c.acceptedAt=at;c.age14Confirmed=true;return c;
    }
}
