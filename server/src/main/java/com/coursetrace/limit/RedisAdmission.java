package com.coursetrace.limit;
import com.coursetrace.common.ApiException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
@Component public class RedisAdmission {
 private final StringRedisTemplate redis;private final RateProperties properties;
 private final DefaultRedisScript<List> script;
 public RedisAdmission(StringRedisTemplate redis,RateProperties properties){this.redis=redis;this.properties=properties;script=new DefaultRedisScript<>();script.setLocation(new ClassPathResource("redis/token-bucket.lua"));script.setResultType(List.class);}
 public void admit(long owner,String scope,String request,String fingerprint){
  if(!properties.isEnabled())return;
  var p=properties.policy(scope);List<?> result;
  // All keys share a hash tag. One script charges both buckets or neither.
  String prefix="coursetrace:limit:{admission}:"+scope+":";
  try{result=redis.execute(script,List.of(prefix+"user:"+owner,prefix+"global",prefix+"request:"+owner+":"+digest(request)),
    ""+p.userCapacity(),""+(p.userSeconds()*1000L),""+p.globalCapacity(),""+(p.globalSeconds()*1000L),digest(fingerprint));
  }catch(RuntimeException unavailable){throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"RATE_LIMIT_UNAVAILABLE","请求额度服务暂不可用，未接受新的模型任务，请稍后再试");}
  if(result==null||result.size()<2)throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"RATE_LIMIT_UNAVAILABLE","请求额度服务暂不可用，请稍后再试");
  long status=((Number)result.get(0)).longValue();
  if(status<0)throw new ApiException(HttpStatus.CONFLICT,"REQUEST_ID_REUSED","该请求编号已用于其他内容");
  if(status==0)throw new RateLimitException((((Number)result.get(1)).longValue()+999)/1000);
 }
 private static String digest(String input){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
