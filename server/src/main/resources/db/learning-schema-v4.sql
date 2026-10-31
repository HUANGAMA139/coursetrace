ALTER TABLE processing_task ADD COLUMN parameters_json TEXT;
ALTER TABLE processing_task ADD COLUMN confirm_resubmit_uncertain BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE task_step ADD COLUMN dispatch_seq INT NOT NULL DEFAULT 0;
ALTER TABLE task_step ADD COLUMN poll_count INT NOT NULL DEFAULT 0;
ALTER TABLE task_step ADD COLUMN failure_count INT NOT NULL DEFAULT 0;
ALTER TABLE task_step ADD COLUMN deadline_at DATETIME(6);
ALTER TABLE outbox_event ADD COLUMN dispatch_seq INT NOT NULL DEFAULT 0;
ALTER TABLE outbox_event ADD CONSTRAINT uk_outbox_dispatch UNIQUE (task_id, run_no, stage, part_id, dispatch_seq);
ALTER TABLE outbox_event DROP CONSTRAINT uk_outbox_step;

CREATE TABLE processing_artifact (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 video_id BIGINT NOT NULL,
 kind VARCHAR(32) NOT NULL,
 fingerprint CHAR(64) NOT NULL,
 bucket_name VARCHAR(63),
 object_key VARCHAR(255),
 size_bytes BIGINT NOT NULL DEFAULT 0,
 sha256 CHAR(64),
 metadata_json TEXT,
 response_json TEXT,
 state VARCHAR(20) NOT NULL,
 request_count INT NOT NULL DEFAULT 0,
 input_tokens BIGINT NOT NULL DEFAULT 0,
 output_tokens BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_artifact_video FOREIGN KEY (video_id) REFERENCES course_video(id),
 CONSTRAINT uk_artifact_input UNIQUE (video_id,kind,fingerprint)
);
CREATE TABLE asr_job (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 video_id BIGINT NOT NULL,
 audio_artifact_id BIGINT NOT NULL,
 config_hash CHAR(64) NOT NULL,
 run_no INT NOT NULL,
 submission_no INT NOT NULL,
 status VARCHAR(24) NOT NULL,
 provider_task_id VARCHAR(100),
 temporary_url VARCHAR(1024),
 temporary_expires_at DATETIME(6),
 raw_artifact_id BIGINT,
 poll_count INT NOT NULL DEFAULT 0,
 error_code VARCHAR(64),
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_asr_video FOREIGN KEY (video_id) REFERENCES course_video(id),
 CONSTRAINT fk_asr_audio FOREIGN KEY (audio_artifact_id) REFERENCES processing_artifact(id),
 CONSTRAINT fk_asr_raw FOREIGN KEY (raw_artifact_id) REFERENCES processing_artifact(id),
 CONSTRAINT uk_asr_submission UNIQUE (audio_artifact_id,config_hash,submission_no),
 CONSTRAINT uk_asr_provider UNIQUE (provider_task_id)
);
CREATE TABLE course_segment (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 video_id BIGINT NOT NULL,
 transcript_artifact_id BIGINT NOT NULL,
 ordinal INT NOT NULL,
 begin_ms BIGINT NOT NULL,
 end_ms BIGINT NOT NULL,
 text TEXT NOT NULL,
 created_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_segment_video FOREIGN KEY (video_id) REFERENCES course_video(id),
 CONSTRAINT fk_segment_transcript FOREIGN KEY (transcript_artifact_id) REFERENCES processing_artifact(id),
 CONSTRAINT uk_segment_ordinal UNIQUE (transcript_artifact_id,ordinal)
);
CREATE INDEX idx_segment_time ON course_segment(video_id,transcript_artifact_id,begin_ms,id);
CREATE TABLE course_note (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 video_id BIGINT NOT NULL,
 transcript_artifact_id BIGINT NOT NULL,
 window_no INT NOT NULL,
 chapter_no INT NOT NULL,
 input_digest CHAR(64) NOT NULL,
 title VARCHAR(120) NOT NULL,
 summary TEXT NOT NULL,
 points_json TEXT NOT NULL,
 source_segment_ids_json TEXT NOT NULL,
 begin_ms BIGINT NOT NULL,
 end_ms BIGINT NOT NULL,
 model VARCHAR(100) NOT NULL,
 prompt_version VARCHAR(40) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_note_video FOREIGN KEY (video_id) REFERENCES course_video(id),
 CONSTRAINT fk_note_transcript FOREIGN KEY (transcript_artifact_id) REFERENCES processing_artifact(id),
 CONSTRAINT uk_note_chapter UNIQUE (input_digest,chapter_no)
);
CREATE INDEX idx_note_video ON course_note(video_id,transcript_artifact_id,window_no,chapter_no);
