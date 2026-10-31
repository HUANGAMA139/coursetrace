package com.coursetrace.agent;
import com.coursetrace.learning.*;
import com.coursetrace.retrieval.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class AgentProtocolTest {
 HttpServer server;List<JsonNode> wire=new ArrayList<>();ObjectMapper json=new ObjectMapper();AgentModelClient client;
 @BeforeEach void setup()throws Exception{
  server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/",e->{
   wire.add(json.readTree(e.getRequestBody().readAllBytes()));
   String message=wire.size()==1?"{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"real-call-1\",\"type\":\"function\",\"function\":{\"name\":\"searchSegments\",\"arguments\":\"{\\\"query\\\":\\\"队列\\\"}\"}}]}":"{\"role\":\"assistant\",\"content\":\"READY\"}";
   String response="{\"id\":\"fixture\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"qwen-fixture\",\"choices\":[{\"index\":0,\"message\":"+message+",\"finish_reason\":\""+(wire.size()==1?"tool_calls":"stop")+"\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";
   byte[] bytes=response.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();
  });server.start();
  client=new AgentModelClient(new LearningProperties()){
   @Override protected ChatModel createModel(RetrievalProperties.Options config,int seconds){
    return OpenAiChatModel.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/v1").apiKey("unit-test-placeholder")
     .modelName("qwen-fixture").customParameters(Map.of("enable_thinking",false)).maxTokens(512).maxRetries(0).strictTools(true).httpClientBuilder(new BoundedAgentHttpClient()).build();
   }
  };
 }
 @AfterEach void stop(){server.stop(0);}
 @Test void pinnedJavaSdkSendsActualRequiredToolAndMatchingResultId(){
  var options=new RetrievalProperties().snapshot(new LearningProperties());var messages=new ArrayList<ChatMessage>();
  messages.add(SystemMessage.from("课程资料工具"));messages.add(UserMessage.from("队列"));
  var first=client.decide(options,messages,true,10);assertThat(first.message().toolExecutionRequests()).hasSize(1);
  var request=first.message().toolExecutionRequests().getFirst();assertThat(request.id()).isEqualTo("real-call-1");
  messages.add(first.message());messages.add(ToolExecutionResultMessage.from(request,"{\"sources\":[]}"));client.decide(options,messages,false,10);
  var initial=wire.getFirst();assertThat(initial.path("tool_choice").asText()).isEqualTo("required");
  assertThat(initial.path("parallel_tool_calls").asBoolean(true)).isFalse();assertThat(initial.path("enable_thinking").asBoolean(true)).isFalse();
  assertThat(initial.path("tools").size()).isEqualTo(1);assertThat(initial.path("tools").get(0).path("function").path("parameters").path("additionalProperties").asBoolean(true)).isFalse();
  assertThat(initial.path("tools").get(0).path("function").path("parameters").path("properties").path("topK").path("type").asText()).isEqualTo("integer");
  var next=wire.getLast();assertThat(next.path("tools").size()).isEqualTo(2);assertThat(next.path("tool_choice").asText()).isEqualTo("auto");
  assertThat(next.path("messages").get(3).path("tool_call_id").asText()).isEqualTo(request.id());assertThat(next.path("messages").get(2).path("tool_calls").get(0).path("id").asText()).isEqualTo(request.id());
 }
 @Test void actualSerializedInputBudgetRejectsBeforeNetwork(){
  var options=new RetrievalProperties().snapshot(new LearningProperties());
  assertThatThrownBy(()->client.decide(options,List.of(UserMessage.from("字".repeat(14000))),true,10)).isInstanceOf(LearningFailure.class);
  assertThat(wire).isEmpty();
 }
}
