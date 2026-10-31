CREATE TABLE video_upload (
 id VARCHAR(36) PRIMARY KEY,
 owner_id BIGINT NOT NULL, course_id BIGINT NOT NULL,
 request_id VARCHAR(64) NOT NULL,
 filename VARCHAR(255) NOT NULL, title VARCHAR(120) NOT NULL,
 file_sha256 VARCHAR(64) NOT NULL, size_bytes BIGINT NOT NULL,
 chunk_size BIGINT NOT NULL, chunk_count INT NOT NULL,
 status VARCHAR(24) NOT NULL, video_id BIGINT,
 execution_token VARCHAR(36), lease_until DATETIME(6), final_key VARCHAR(512),
 last_error VARCHAR(300), expires_at DATETIME(6) NOT NULL,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 cleaned_at DATETIME(6),
 CONSTRAINT uk_upload_request UNIQUE(owner_id,course_id,request_id),
 CONSTRAINT fk_upload_course FOREIGN KEY(course_id) REFERENCES course(id)
);
CREATE INDEX idx_upload_owner ON video_upload(owner_id,course_id,file_sha256,status);
CREATE INDEX idx_upload_cleanup ON video_upload(status,expires_at,cleaned_at);
CREATE TABLE video_upload_part (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 upload_id VARCHAR(36) NOT NULL, part_no INT NOT NULL,
 object_key VARCHAR(512) NOT NULL, sha256 VARCHAR(64) NOT NULL,
 size_bytes BIGINT NOT NULL, created_at DATETIME(6) NOT NULL,
 CONSTRAINT uk_upload_part UNIQUE(upload_id,part_no),
 CONSTRAINT fk_part_upload FOREIGN KEY(upload_id) REFERENCES video_upload(id)
);
