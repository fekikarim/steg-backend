package tn.steg.backend.certificate.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tn.steg.backend.common.domain.model.BaseEntity;
import tn.steg.backend.document.domain.model.FileAsset;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.organization.domain.model.Employee;

import java.time.Instant;
import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "certificates")
public class Certificate extends BaseEntity {

    @Column(name = "reference", nullable = false, unique = true, length = 50)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private CertificateStatus status = CertificateStatus.GENERATED;

    @Column(name = "template_code", nullable = false, length = 50)
    private String templateCode;

    @Column(name = "template_version", nullable = false)
    private Integer templateVersion = 1;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    @Column(name = "issued_at")
    private Instant issuedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "internship_id", nullable = false)
    private Internship internship;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "generated_by_id", nullable = false)
    private Employee generatedBy;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_asset_id", nullable = false)
    private FileAsset pdfFile;

    public Certificate(String reference, Internship internship, Employee generatedBy, FileAsset pdfFile,
                       String templateCode, Integer templateVersion, LocalDate issueDate) {
        this.reference = reference;
        this.internship = internship;
        this.generatedBy = generatedBy;
        this.pdfFile = pdfFile;
        this.templateCode = templateCode;
        this.templateVersion = templateVersion;
        this.issueDate = issueDate;
        this.generatedAt = Instant.now();
        this.status = CertificateStatus.GENERATED;
    }
}
