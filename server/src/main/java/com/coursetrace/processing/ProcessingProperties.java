package com.coursetrace.processing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Min;

@Component
@Validated
@ConfigurationProperties("coursetrace.processing")
public class ProcessingProperties {
    @Min(1) private int maxPublishAttempts = 20;
    @Min(1) private int staleMinutes = 10;
    public int getMaxPublishAttempts() { return maxPublishAttempts; }
    public void setMaxPublishAttempts(int value) { maxPublishAttempts = value; }
    public int getStaleMinutes() { return staleMinutes; }
    public void setStaleMinutes(int value) { staleMinutes = value; }
}
