package com.coursetrace.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("coursetrace.storage")
public record StorageProperties(String endpoint, String accessKey, String secretKey, String bucket) {}
