CREATE TABLE processing_task (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 video_id BIGINT NOT NULL,
 pipeline_version INT NOT NULL,
 run_no INT NOT NULL,
 current_stage VARCHAR(40) NOT NULL,
 status VARCHAR(20) NOT NULL,
 error_code VARCHAR(64),
 error_message VARCHAR(255),
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 started_at DATETIME(6),
 finished_at DATETIME(6),
 CONSTRAINT fk_task_video FOREIGN KEY (video_id) REFERENCES course_video(id),
 CONSTRAINT uk_task_video_pipeline UNIQUE (video_id, pipeline_version)
);
CREATE TABLE task_step (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 task_id BIGINT NOT NULL,
 run_no INT NOT NULL,
 stage VARCHAR(40) NOT NULL,
 part_id INT NOT NULL,
 status VARCHAR(20) NOT NULL,
 attempt_count INT NOT NULL,
 max_attempts INT NOT NULL,
 execution_token VARCHAR(36),
 result_json TEXT,
 error_code VARCHAR(64),
 error_message VARCHAR(255),
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 started_at DATETIME(6),
 finished_at DATETIME(6),
 CONSTRAINT fk_step_task FOREIGN KEY (task_id) REFERENCES processing_task(id),
 CONSTRAINT uk_step_execution UNIQUE (task_id, run_no, stage, part_id)
);
CREATE TABLE outbox_event (
 event_id VARCHAR(36) PRIMARY KEY,
 task_id BIGINT NOT NULL,
 run_no INT NOT NULL,
 pipeline_version INT NOT NULL,
 stage VARCHAR(40) NOT NULL,
 part_id INT NOT NULL,
 topic VARCHAR(100) NOT NULL,
 message_key VARCHAR(64) NOT NULL,
 payload_json TEXT NOT NULL,
 status VARCHAR(20) NOT NULL,
 publish_attempts INT NOT NULL,
 next_attempt_at DATETIME(6) NOT NULL,
 last_error VARCHAR(255),
 created_at DATETIME(6) NOT NULL,
 sent_at DATETIME(6),
 CONSTRAINT fk_outbox_task FOREIGN KEY (task_id) REFERENCES processing_task(id),
 CONSTRAINT uk_outbox_step UNIQUE (task_id, run_no, stage, part_id)
);
CREATE INDEX idx_outbox_due ON outbox_event (status, next_attempt_at, created_at);
CREATE INDEX idx_task_status ON processing_task (status, updated_at);
