package com.coursetrace.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("coursetrace.media")
public record MediaProperties(String ffprobePath, String tempDirectory, int probeTimeoutSeconds) {}
