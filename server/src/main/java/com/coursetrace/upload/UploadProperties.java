package com.coursetrace.upload;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
@Component @ConfigurationProperties("coursetrace.upload")
public class UploadProperties {
 private long chunkBytes=8*1024*1024;
 private int expireHours=24,maxSessions=3;
 public long getChunkBytes(){return chunkBytes;} public void setChunkBytes(long n){chunkBytes=n;}
 public int getExpireHours(){return expireHours;} public void setExpireHours(int n){expireHours=n;}
 public int getMaxSessions(){return maxSessions;} public void setMaxSessions(int n){maxSessions=n;}
}
