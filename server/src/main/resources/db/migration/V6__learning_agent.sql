ALTER TABLE chat_turn ADD COLUMN mode VARCHAR(12) NOT NULL DEFAULT 'RAG';
CREATE TABLE agent_run (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, turn_id BIGINT NOT NULL UNIQUE,
 owner_id BIGINT NOT NULL, course_id BIGINT NOT NULL, video_id BIGINT NOT NULL,
 index_id BIGINT NOT NULL, transcript_artifact_id BIGINT NOT NULL,
 config_json TEXT NOT NULL, status VARCHAR(32) NOT NULL, phase VARCHAR(40) NOT NULL,
 stop_reason VARCHAR(64), limited BOOLEAN NOT NULL DEFAULT FALSE,
 rounds INT NOT NULL DEFAULT 0, tool_calls INT NOT NULL DEFAULT 0,
 search_calls INT NOT NULL DEFAULT 0, context_calls INT NOT NULL DEFAULT 0,
 result_bytes INT NOT NULL DEFAULT 0, evidence_json TEXT,
 execution_token CHAR(36) NOT NULL, created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL, deadline_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_agent_turn FOREIGN KEY(turn_id) REFERENCES chat_turn(id)
);
CREATE TABLE agent_step (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, run_id BIGINT NOT NULL, seq INT NOT NULL,
 kind VARCHAR(12) NOT NULL, phase VARCHAR(40) NOT NULL, name VARCHAR(80),
 tool_call_id VARCHAR(160), args_json TEXT, result_json TEXT,
 status VARCHAR(24) NOT NULL, cached BOOLEAN NOT NULL DEFAULT FALSE,
 source_count INT NOT NULL DEFAULT 0, elapsed_ms BIGINT NOT NULL DEFAULT 0,
 input_tokens BIGINT NOT NULL DEFAULT 0, output_tokens BIGINT NOT NULL DEFAULT 0,
 error_code VARCHAR(64), created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_agent_step_run FOREIGN KEY(run_id) REFERENCES agent_run(id),
 CONSTRAINT uk_agent_step_seq UNIQUE(run_id,seq)
);
