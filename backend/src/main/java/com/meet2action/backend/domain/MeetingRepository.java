package com.meet2action.backend.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeetingRepository extends JpaRepository<Meeting, String> {

    Optional<Meeting> findFirstByTranscriptHashAndExtractionStatusInAndCreatedAtAfterOrderByCreatedAtDesc(
            String transcriptHash, Collection<ExtractionStatus> statuses, Instant after);
}
