package com.coursetrace.retrieval;
import com.coursetrace.learning.*;
import com.coursetrace.retrieval.RetrievalProperties.Options;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.*;

@Component public class QwenEmbeddingClient {
 private final LearningProperties learning;private final LearningJson json;
 public QwenEmbeddingClient(LearningProperties learning,LearningJson json){this.learning=learning;this.json=json;}
 public boolean configured(){try{QwenAsrClient.official(learning.getChatBase(),true);return !learning.getApiKey().isBlank();}catch(RuntimeException e){return false;}}
 public Result embed(Options config,List<String> input,int timeoutSeconds){
  if(!configured())throw LearningFailure.permanent("AI_NOT_CONFIGURED","检索模型服务尚未配置");
  QwenAsrClient.official(config.embeddingBase(),true);
  if(input.isEmpty()||input.size()>10||input.stream().anyMatch(s->s==null||s.isBlank()||s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>6000))
   throw LearningFailure.permanent("EMBEDDING_INPUT_INVALID","向量输入超过限制");
  try {
   var model=OpenAiEmbeddingModel.builder().baseUrl(config.embeddingBase()).apiKey(learning.getApiKey())
    .modelName(config.embeddingModel()).dimensions(config.dimensions()).maxRetries(0).httpClientBuilder(new OrderedEmbeddingHttpClient(json))
    .timeout(Duration.ofSeconds(Math.max(1,Math.min(timeoutSeconds,45)))).logRequests(false).logResponses(false).build();
   var response=model.embedAll(input.stream().map(TextSegment::from).toList());
   var vectors=response.content().stream().map(e->e.vector()).toList();
   validate(vectors,input.size(),config.dimensions());
   var usage=response.tokenUsage();
   return new Result(vectors,usage==null||usage.inputTokenCount()==null?0:usage.inputTokenCount());
  }catch(LearningFailure e){throw e;}
  catch(Exception e) {
   for(Throwable cause=e;cause!=null;cause=cause.getCause())if(cause instanceof LearningFailure invalid)throw invalid;
   String type=e.getClass().getSimpleName();
   if(type.contains("Authentication")||type.contains("InvalidRequest")||type.contains("Permission"))
    throw LearningFailure.permanent("EMBEDDING_REJECTED","向量服务拒绝请求，请检查模型与配置");
   throw new LearningFailure("EMBEDDING_UNAVAILABLE","向量服务暂时不可用，请稍后恢复",true);
  }
 }
 public static void validate(List<float[]> vectors,int expected,int dimensions){
  if(vectors==null||vectors.size()!=expected)throw LearningFailure.permanent("INVALID_EMBEDDING","返回向量数量不正确");
  for(var vector:vectors){
   if(vector==null||vector.length!=dimensions)throw LearningFailure.permanent("INVALID_EMBEDDING","返回向量维度不正确");
   double norm=0;for(float v:vector){if(!Float.isFinite(v))throw LearningFailure.permanent("INVALID_EMBEDDING","返回向量包含无效数值");norm+=(double)v*v;}
   if(norm<1e-24)throw LearningFailure.permanent("INVALID_EMBEDDING","返回向量不可用于检索");
  }
 }
 public record Result(List<float[]> vectors,long inputTokens){}
}
