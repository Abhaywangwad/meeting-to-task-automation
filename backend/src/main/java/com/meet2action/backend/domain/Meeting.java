package com.meet2action.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.Length;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "meetings", indexes = {
        @Index(name = "idx_meetings_hash_created", columnList = "transcript_hash, created_at")
})
public class Meeting {

    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id = UUID.randomUUID().toString();

    @Column(length = Length.LONG32, nullable = false, updatable = false)
    private String transcript;

    @Column(name = "transcript_hash", length = 64, nullable = false, updatable = false)
    private String transcriptHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(name = "extraction_status", length = 16, nullable = false)
    private ExtractionStatus extractionStatus = ExtractionStatus.PENDING;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    protected Meeting() {}

    public Meeting(String transcript, String transcriptHash) {
        this.transcript = transcript;
        this.transcriptHash = transcriptHash;
    }

    public void markCompleted() {
        this.extractionStatus = ExtractionStatus.COMPLETED;
        this.errorCode = null;
        this.errorMessage = null;
    }

    public void markFailed(String code, String message) {
        this.extractionStatus = ExtractionStatus.FAILED;
        this.errorCode = code;
        this.errorMessage = message == null ? null : message.substring(0, Math.min(message.length(), 1000));
    }

    public String getId() { return id; }
    public String getTranscript() { return transcript; }
    public String getTranscriptHash() { return transcriptHash; }
    public Instant getCreatedAt() { return createdAt; }
    public ExtractionStatus getExtractionStatus() { return extractionStatus; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
}
