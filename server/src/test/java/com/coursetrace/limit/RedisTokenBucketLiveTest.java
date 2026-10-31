package com.coursetrace.limit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in; uses unique test keys and never flushes Redis or calls AI. */
@EnabledIfEnvironmentVariable(named="COURSETRACE_LIVE_REDIS",matches="true")
class RedisTokenBucketLiveTest {
 static LettuceConnectionFactory factory;static StringRedisTemplate redis;static DefaultRedisScript<List> script;
 String prefix;
 @BeforeAll static void connect(){factory=new LettuceConnectionFactory("127.0.0.1",6379);factory.afterPropertiesSet();factory.start();redis=new StringRedisTemplate(factory);redis.afterPropertiesSet();script=new DefaultRedisScript<>();script.setLocation(new ClassPathResource("redis/token-bucket.lua"));script.setResultType(List.class);}
 @AfterAll static void close(){factory.destroy();}
 @BeforeEach void unique(){prefix="coursetrace-test:"+UUID.randomUUID()+":";}
 List<?> call(String user,String request,String fingerprint,int uc,int ui,int gc,int gi){return redis.execute(script,List.of(prefix+user,prefix+"global",prefix+"request:"+request),""+uc,""+ui,""+gc,""+gi,fingerprint);}
 @AfterEach void cleanup(){var keys=redis.keys(prefix+"*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);}
 @Test void globalDenialDoesNotSpendUserTokens(){assertThat(call("alice","a","q",1,60000,1,60000).getFirst()).isEqualTo(1L);assertThat(call("bob","b","q",1,60000,1,60000).getFirst()).isEqualTo(0L);assertThat(redis.hasKey(prefix+"bob")).isFalse();}
 @Test void sameRequestReusesAdmissionButChangedContentConflicts(){assertThat(call("alice","same","q1",1,60000,1,60000).getFirst()).isEqualTo(1L);assertThat(call("alice","same","q1",1,60000,1,60000).getFirst()).isEqualTo(1L);assertThat(call("alice","same","q2",1,60000,1,60000).getFirst()).isEqualTo(-1L);assertThat(call("alice","other","q",1,60000,1,60000).getFirst()).isEqualTo(0L);}
 @Test void refillsUsingRedisClockAndExpiresIdleBuckets()throws Exception{assertThat(call("alice","a","q",1,200,10,200).getFirst()).isEqualTo(1L);var denied=call("alice","b","q",1,200,10,200);assertThat(denied.getFirst()).isEqualTo(0L);assertThat(((Number)denied.get(1)).longValue()).isBetween(1L,200L);Thread.sleep(250);assertThat(call("alice","b","q",1,200,10,200).getFirst()).isEqualTo(1L);assertThat(redis.getExpire(prefix+"alice")).isBetween(1L,61L);}
 @Test void atomicConcurrentRequestsCannotOverdrawGlobalBucket()throws Exception{try(var executor=Executors.newFixedThreadPool(12)){var jobs=new ArrayList<Callable<Long>>();for(int n=0;n<24;n++){int id=n;jobs.add(()->((Number)call("user"+id,"r"+id,"q",2,60000,8,60000).getFirst()).longValue());}long accepted=0;for(var result:executor.invokeAll(jobs))accepted+=result.get();assertThat(accepted).isEqualTo(8);}}
 @Test void userBucketsAreIsolated(){assertThat(call("alice","a","q",1,60000,10,60000).getFirst()).isEqualTo(1L);assertThat(call("alice","b","q",1,60000,10,60000).getFirst()).isEqualTo(0L);assertThat(call("bob","c","q",1,60000,10,60000).getFirst()).isEqualTo(1L);}
}
