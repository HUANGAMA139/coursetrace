package com.coursetrace.agent;
import com.coursetrace.learning.*;
import com.coursetrace.retrieval.RetrievalProperties.Options;
import dev.langchain4j.model.openai.*;
import dev.langchain4j.model.chat.request.*;
import dev.langchain4j.model.chat.request.json.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.agent.tool.*;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.*;
@Component public class AgentModelClient {
 private final LearningProperties learning;
 public AgentModelClient(LearningProperties learning){this.learning=learning;}
 public static List<ToolSpecification> tools(boolean first){
  var search=ToolSpecification.builder().name("searchSegments").description("搜索当前视频的课程原文。只返回命中内容，不自动补读。可改写查询。")
   .parameters(JsonObjectSchema.builder().addStringProperty("query").addIntegerProperty("topK","默认2，范围1到3").required("query","topK").additionalProperties(false).build()).build();
  var read=ToolSpecification.builder().name("readSurroundingContext").description("补读本轮已经搜索到的片段及前后邻句。需要解释前后联系、指代或搜索结果截断时使用。")
   .parameters(JsonObjectSchema.builder().addIntegerProperty("segmentId").addIntegerProperty("before","0到3，默认2")
    .addIntegerProperty("after","0到3，默认2").addIntegerProperty("startOffset","默认0，长句可用返回的nextOffset继续")
    .required("segmentId","before","after","startOffset").additionalProperties(false).build()).build();
  return first?List.of(search):List.of(search,read);
 }
 public Result decide(Options config,List<ChatMessage> messages,boolean first,int seconds){
  QwenAsrClient.official(config.embeddingBase(),true);
  try{
   var model=createModel(config,seconds);

   var params=OpenAiChatRequestParameters.builder().toolSpecifications(tools(first))
    .toolChoice(first?ToolChoice.REQUIRED:ToolChoice.AUTO).parallelToolCalls(false).maxOutputTokens(512).build();
   var response=model.chat(ChatRequest.builder().messages(messages).parameters(params).build());var usage=response.tokenUsage();
   return new Result(response.aiMessage(),usage==null||usage.inputTokenCount()==null?0:usage.inputTokenCount(),usage==null||usage.outputTokenCount()==null?0:usage.outputTokenCount());
  }catch(LearningFailure e){throw e;}catch(Exception e){for(Throwable cause=e;cause!=null;cause=cause.getCause())if(cause instanceof LearningFailure known)throw known;throw new LearningFailure("AGENT_MODEL_UNKNOWN","模型结果未能确认，请查看本次记录，不会自动重发",false,true);}
 }
 protected dev.langchain4j.model.chat.ChatModel createModel(Options config,int seconds){
  return OpenAiChatModel.builder().baseUrl(config.embeddingBase()).apiKey(learning.getApiKey()).modelName(config.chatModel())
   .customParameters(Map.of("enable_thinking",false)).maxTokens(512).maxRetries(0).strictTools(true).httpClientBuilder(new BoundedAgentHttpClient())
   .timeout(Duration.ofSeconds(Math.max(1,Math.min(20,seconds)))).logRequests(false).logResponses(false).build();
 }
 public record Result(AiMessage message,long inputTokens,long outputTokens){}
}
