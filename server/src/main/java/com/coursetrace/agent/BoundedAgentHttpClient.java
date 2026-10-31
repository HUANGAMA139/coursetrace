package com.coursetrace.agent;
import com.coursetrace.learning.LearningFailure;
import dev.langchain4j.http.client.*;
import dev.langchain4j.http.client.sse.*;
import java.time.Duration;
/** Enforce the byte budget on the actual serialized request, before any network write. */
public class BoundedAgentHttpClient implements HttpClientBuilder {
 private Duration connect=Duration.ofSeconds(5),read=Duration.ofSeconds(20);
 public Duration connectTimeout(){return connect;}public HttpClientBuilder connectTimeout(Duration v){connect=v;return this;}
 public Duration readTimeout(){return read;}public HttpClientBuilder readTimeout(Duration v){read=v;return this;}
 public HttpClient build(){
  var delegate=HttpClientBuilderLoader.loadHttpClientBuilder().connectTimeout(connect).readTimeout(read).build();
  return new HttpClient(){
   public SuccessfulHttpResponse execute(HttpRequest request){
    if(request.body()!=null&&AgentToolService.bytes(request.body())>40000)throw LearningFailure.permanent("AGENT_INPUT_LIMIT","本次模型请求超过输入预算");
    return delegate.execute(request);
   }
   public void execute(HttpRequest request,ServerSentEventParser p,ServerSentEventListener l){throw new UnsupportedOperationException("Streaming is not supported");}
  };
 }
}
