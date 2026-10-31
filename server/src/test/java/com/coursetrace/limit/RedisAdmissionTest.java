package com.coursetrace.limit;
import com.coursetrace.common.ApiException;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
class RedisAdmissionTest {
 private StringRedisTemplate redis;private RateProperties config;private RedisAdmission admission;
 @BeforeEach void setup(){redis=mock(StringRedisTemplate.class);config=new RateProperties();admission=new RedisAdmission(redis,config);}
 @Test void acceptsReservation(){when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(List.of(1L,0L));assertThatCode(()->admission.admit(1,"qa","id","question")).doesNotThrowAnyException();}
 @Test void denialCarriesRetryAfterRoundedUp(){when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(List.of(0L,1001L));assertThatThrownBy(()->admission.admit(1,"qa","id","question")).isInstanceOfSatisfying(RateLimitException.class,e->assertThat(e.retryAfterSeconds()).isEqualTo(2));}
 @Test void redisFailureRejectsNewWork(){when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenThrow(new RuntimeException("offline"));assertThatThrownBy(()->admission.admit(1,"qa","id","question")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getCode()).isEqualTo("RATE_LIMIT_UNAVAILABLE"));}
 @Test void fingerprintConflictIsRejected(){when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(List.of(-1L,0L));assertThatThrownBy(()->admission.admit(1,"qa","id","changed")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getCode()).isEqualTo("REQUEST_ID_REUSED"));}
 @Test void isolatedTestProfileCanDisableAdmission(){config.setEnabled(false);admission.admit(1,"qa","id","question");verifyNoInteractions(redis);}
 @Test void invalidPolicyCannotSendRedisCommand(){config.setQaCapacity(0);assertThatThrownBy(()->admission.admit(1,"qa","id","question")).isInstanceOf(IllegalStateException.class);verifyNoInteractions(redis);}
}
