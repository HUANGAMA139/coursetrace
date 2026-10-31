CREATE TABLE course_video (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    bucket_name VARCHAR(63) NOT NULL,
    object_key VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL,
    content_type VARCHAR(64) NOT NULL,
    duration_ms BIGINT NOT NULL,
    width INT NOT NULL,
    height INT NOT NULL,
    video_codec VARCHAR(32) NOT NULL,
    audio_codec VARCHAR(32),
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_video_course FOREIGN KEY (course_id) REFERENCES course(id),
    CONSTRAINT uk_video_object UNIQUE (bucket_name, object_key)
);
CREATE INDEX idx_video_course_created ON course_video (course_id, created_at, id);
