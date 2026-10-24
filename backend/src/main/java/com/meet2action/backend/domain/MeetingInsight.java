package com.meet2action.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.Length;
import java.util.UUID;

/** A decision, unresolved question or completed update extracted from a meeting. Read-only after creation. */
@Entity
@Table(name = "meeting_insights", indexes = {
        @Index(name = "idx_insights_meeting_type", columnList = "meeting_id, type, position")
})
public class MeetingInsight {

    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id = UUID.randomUUID().toString();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meeting_id", nullable = false, updatable = false)
    private Meeting meeting;

    @Enumerated(EnumType.STRING)
    @Column(length = 24, nullable = false)
    private InsightType type;

    @Column(nullable = false)
    private int position;

    @Column(length = Length.LONG32, nullable = false)
    private String text;

    @Column(length = 255)
    private String owner;

    @Column(length = Length.LONG32, name = "evidence_quote", nullable = false)
    private String evidenceQuote = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_status", length = 16, nullable = false)
    private EvidenceStatus evidenceStatus;

    @Column(name = "evidence_start")
    private Integer evidenceStart;

    @Column(name = "evidence_end")
    private Integer evidenceEnd;

    @Column(name = "evidence_match", length = 32)
    private String evidenceMatch;

    protected MeetingInsight() {}

    public MeetingInsight(Meeting meeting, InsightType type, int position, String text, String owner,
                          String evidenceQuote, EvidenceStatus evidenceStatus, Integer evidenceStart,
                          Integer evidenceEnd, String evidenceMatch) {
        this.meeting = meeting;
        this.type = type;
        this.position = position;
        this.text = text;
        this.owner = owner;
        this.evidenceQuote = evidenceQuote == null ? "" : evidenceQuote;
        this.evidenceStatus = evidenceStatus;
        this.evidenceStart = evidenceStart;
        this.evidenceEnd = evidenceEnd;
        this.evidenceMatch = evidenceMatch;
    }

    public String getId() { return id; }
    public Meeting getMeeting() { return meeting; }
    public InsightType getType() { return type; }
    public int getPosition() { return position; }
    public String getText() { return text; }
    public String getOwner() { return owner; }
    public String getEvidenceQuote() { return evidenceQuote; }
    public EvidenceStatus getEvidenceStatus() { return evidenceStatus; }
    public Integer getEvidenceStart() { return evidenceStart; }
    public Integer getEvidenceEnd() { return evidenceEnd; }
    public String getEvidenceMatch() { return evidenceMatch; }
}
