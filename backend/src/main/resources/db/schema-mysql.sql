-- Meet2Action schema for MySQL 8+. Idempotent (IF NOT EXISTS); applied at startup by SchemaInitializer
-- when the database is reachable. It never drops anything. Columns added after the first release
-- (Trello sync) are added to existing tables by SchemaInitializer, because MySQL has no ADD COLUMN IF NOT EXISTS.

CREATE TABLE IF NOT EXISTS meetings (
  id                varchar(36)  NOT NULL,
  transcript        longtext     NOT NULL,
  transcript_hash   varchar(64)  NOT NULL,
  created_at        datetime(6)  NOT NULL,
  extraction_status enum('PENDING','COMPLETED','FAILED') NOT NULL,
  error_code        varchar(64)  DEFAULT NULL,
  error_message     varchar(1000) DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_meetings_hash_created (transcript_hash, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS task_proposals (
  id              varchar(36)  NOT NULL,
  meeting_id      varchar(36)  NOT NULL,
  position        int          NOT NULL,
  title           varchar(500) NOT NULL,
  description     longtext     NOT NULL,
  owner           varchar(255) DEFAULT NULL,
  deadline        varchar(255) DEFAULT NULL,
  evidence_quote  longtext     NOT NULL,
  evidence_status enum('UNVERIFIED','VERIFIED') NOT NULL,
  evidence_start  int          DEFAULT NULL,
  evidence_end    int          DEFAULT NULL,
  evidence_match  varchar(32)  DEFAULT NULL,
  confidence      double       DEFAULT NULL,
  review_status   enum('APPROVED','PENDING_REVIEW','REJECTED') NOT NULL,
  sync_status     enum('NOT_SYNCED','SYNCING','SYNCED','FAILED','UNCERTAIN') NOT NULL,
  version         bigint       NOT NULL,
  created_at      datetime(6)  NOT NULL,
  updated_at      datetime(6)  NOT NULL,
  approved_at     datetime(6)  DEFAULT NULL,
  rejected_at     datetime(6)  DEFAULT NULL,
  trello_card_id     varchar(64)  DEFAULT NULL,
  trello_card_url    varchar(512) DEFAULT NULL,
  trello_list_id     varchar(64)  DEFAULT NULL,
  synced_at          datetime(6)  DEFAULT NULL,
  sync_attempted_at  datetime(6)  DEFAULT NULL,
  sync_error_code    varchar(64)  DEFAULT NULL,
  sync_error_message varchar(500) DEFAULT NULL,
  synced_version     bigint       DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_tasks_meeting (meeting_id, position),
  KEY idx_tasks_review_status (review_status),
  CONSTRAINT fk_tasks_meeting FOREIGN KEY (meeting_id) REFERENCES meetings (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS meeting_insights (
  id              varchar(36)  NOT NULL,
  meeting_id      varchar(36)  NOT NULL,
  type            enum('COMPLETED_UPDATE','DECISION','UNRESOLVED_QUESTION') NOT NULL,
  position        int          NOT NULL,
  text            longtext     NOT NULL,
  owner           varchar(255) DEFAULT NULL,
  evidence_quote  longtext     NOT NULL,
  evidence_status enum('UNVERIFIED','VERIFIED') NOT NULL,
  evidence_start  int          DEFAULT NULL,
  evidence_end    int          DEFAULT NULL,
  evidence_match  varchar(32)  DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_insights_meeting_type (meeting_id, type, position),
  CONSTRAINT fk_insights_meeting FOREIGN KEY (meeting_id) REFERENCES meetings (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
