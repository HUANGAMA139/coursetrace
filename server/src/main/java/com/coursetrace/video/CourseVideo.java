package com.coursetrace.video;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("course_video")
public class CourseVideo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long courseId;
    private String title;
    private String originalFilename;
    private String bucketName;
    private String objectKey;
    private Long sizeBytes;
    private String contentType;
    private Long durationMs;
    private Integer width;
    private Integer height;
    private String videoCodec;
    private String audioCodec;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }
    public Long getCourseId() { return courseId; }
    public void setCourseId(Long value) { this.courseId = value; }
    public String getTitle() { return title; }
    public void setTitle(String value) { this.title = value; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String value) { this.originalFilename = value; }
    public String getBucketName() { return bucketName; }
    public void setBucketName(String value) { this.bucketName = value; }
    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String value) { this.objectKey = value; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long value) { this.sizeBytes = value; }
    public String getContentType() { return contentType; }
    public void setContentType(String value) { this.contentType = value; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long value) { this.durationMs = value; }
    public Integer getWidth() { return width; }
    public void setWidth(Integer value) { this.width = value; }
    public Integer getHeight() { return height; }
    public void setHeight(Integer value) { this.height = value; }
    public String getVideoCodec() { return videoCodec; }
    public void setVideoCodec(String value) { this.videoCodec = value; }
    public String getAudioCodec() { return audioCodec; }
    public void setAudioCodec(String value) { this.audioCodec = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { this.createdAt = value; }
}
