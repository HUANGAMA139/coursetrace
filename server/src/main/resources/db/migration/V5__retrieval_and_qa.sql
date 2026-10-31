CREATE TABLE video_search_index (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 video_id BIGINT NOT NULL, owner_id BIGINT NOT NULL, course_id BIGINT NOT NULL,
 transcript_artifact_id BIGINT NOT NULL, task_id BIGINT NOT NULL,
 fingerprint CHAR(64) NOT NULL, config_json TEXT NOT NULL,
 collection_name VARCHAR(120) NOT NULL, request_id VARCHAR(64) NOT NULL,
 status VARCHAR(24) NOT NULL, chunk_count INT NOT NULL DEFAULT 0,
 batch_count INT NOT NULL DEFAULT 0, error_code VARCHAR(64),
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_search_video FOREIGN KEY(video_id) REFERENCES course_video(id),
 CONSTRAINT fk_search_transcript FOREIGN KEY(transcript_artifact_id) REFERENCES processing_artifact(id),
 CONSTRAINT fk_search_task FOREIGN KEY(task_id) REFERENCES processing_task(id),
 CONSTRAINT uk_search_request UNIQUE(video_id,request_id)
);
CREATE TABLE video_search_state (
 video_id BIGINT PRIMARY KEY, active_index_id BIGINT, building_index_id BIGINT,
 updated_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_search_state_video FOREIGN KEY(video_id) REFERENCES course_video(id),
 CONSTRAINT fk_search_active FOREIGN KEY(active_index_id) REFERENCES video_search_index(id),
 CONSTRAINT fk_search_building FOREIGN KEY(building_index_id) REFERENCES video_search_index(id)
);
CREATE TABLE subtitle_chunk (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, index_id BIGINT NOT NULL,
 ordinal INT NOT NULL, text TEXT NOT NULL, content_hash CHAR(64) NOT NULL,
 point_id CHAR(36) NOT NULL, begin_ms BIGINT NOT NULL, end_ms BIGINT NOT NULL,
 CONSTRAINT fk_chunk_index FOREIGN KEY(index_id) REFERENCES video_search_index(id),
 CONSTRAINT uk_chunk_ordinal UNIQUE(index_id,ordinal),
 CONSTRAINT uk_chunk_point UNIQUE(point_id)
);
CREATE TABLE subtitle_chunk_segment (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, chunk_id BIGINT NOT NULL, segment_id BIGINT NOT NULL,
 ordinal INT NOT NULL, start_offset INT NOT NULL, end_offset INT NOT NULL,
 CONSTRAINT fk_chunk_source FOREIGN KEY(chunk_id) REFERENCES subtitle_chunk(id),
 CONSTRAINT fk_chunk_segment FOREIGN KEY(segment_id) REFERENCES course_segment(id),
 CONSTRAINT uk_chunk_source UNIQUE(chunk_id,ordinal)
);
CREATE TABLE chat_turn (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, owner_id BIGINT NOT NULL,
 video_id BIGINT NOT NULL, index_id BIGINT NOT NULL, transcript_artifact_id BIGINT NOT NULL,
 request_id VARCHAR(64) NOT NULL, question VARCHAR(2000) NOT NULL, status VARCHAR(32) NOT NULL,
 answer_json TEXT, context_json TEXT, response_json TEXT,
 error_code VARCHAR(64), error_message VARCHAR(240),
 execution_token CHAR(36) NOT NULL, embedding_requests INT NOT NULL DEFAULT 0,
 chat_requests INT NOT NULL DEFAULT 0, input_tokens BIGINT NOT NULL DEFAULT 0,
 output_tokens BIGINT NOT NULL DEFAULT 0, elapsed_ms BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, deadline_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_chat_owner FOREIGN KEY(owner_id) REFERENCES app_user(id),
 CONSTRAINT fk_chat_video FOREIGN KEY(video_id) REFERENCES course_video(id),
 CONSTRAINT fk_chat_index FOREIGN KEY(index_id) REFERENCES video_search_index(id),
 CONSTRAINT uk_chat_request UNIQUE(owner_id,video_id,request_id)
);
CREATE INDEX idx_chat_owner_time ON chat_turn(owner_id,created_at);
CREATE INDEX idx_chat_video_history ON chat_turn(owner_id,video_id,id);
