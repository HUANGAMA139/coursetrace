package com.coursetrace.limit;
import com.coursetrace.common.ApiException;
import org.springframework.http.HttpStatus;
public class RateLimitException extends ApiException {
 private final long retryAfterSeconds;
 public RateLimitException(long seconds){super(HttpStatus.TOO_MANY_REQUESTS,"AI_RATE_LIMIT","提交较频繁，请稍后再试");retryAfterSeconds=Math.max(1,seconds);}
 public long retryAfterSeconds(){return retryAfterSeconds;}
}
