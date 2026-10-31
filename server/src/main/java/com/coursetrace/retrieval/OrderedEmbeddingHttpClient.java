package com.coursetrace.retrieval;
import com.coursetrace.learning.*;
import com.fasterxml.jackson.databind.node.*;
import dev.langchain4j.http.client.*;

import dev.langchain4j.http.client.sse.*;
import java.time.Duration;
/** Validate response indexes before LangChain4j converts data to a list without source indexes. */
public class OrderedEmbeddingHttpClient implements HttpClientBuilder {
 private final LearningJson json;private Duration connect=Duration.ofSeconds(5),read=Duration.ofSeconds(45);
 public OrderedEmbeddingHttpClient(LearningJson json){this.json=json;}
 public Duration connectTimeout(){return connect;}public HttpClientBuilder connectTimeout(Duration v){connect=v;return this;}
 public Duration readTimeout(){return read;}public HttpClientBuilder readTimeout(Duration v){read=v;return this;}
 public HttpClient build(){
  var delegate=HttpClientBuilderLoader.loadHttpClientBuilder().connectTimeout(connect).readTimeout(read).build();
  return new HttpClient(){
   public SuccessfulHttpResponse execute(HttpRequest request){
    var response=delegate.execute(request);
    return SuccessfulHttpResponse.builder().statusCode(response.statusCode()).headers(response.headers()).body(order(request.body(),response.body())).build();
   }
   public void execute(HttpRequest request,ServerSentEventParser parser,ServerSentEventListener listener){throw new UnsupportedOperationException("Embedding streaming is not supported");}
  };
 }
 String order(String input,String output){
  if(output==null||output.length()>1000000)throw invalid();
  var request=json.read(input);var response=json.read(output);
  int expected=request.path("input").isArray()?request.path("input").size():1;var data=response.path("data");
  if(!(response instanceof ObjectNode object)||expected<1||expected>10||!data.isArray()||data.size()!=expected)throw invalid();
  var rows=new com.fasterxml.jackson.databind.JsonNode[expected];
  for(var row:data){var index=row.path("index");if(!index.isIntegralNumber()||!index.canConvertToInt())throw invalid();int n=index.asInt();if(n<0||n>=expected||rows[n]!=null)throw invalid();rows[n]=row;}
  var ordered=JsonNodeFactory.instance.arrayNode();for(var row:rows){if(row==null)throw invalid();ordered.add(row);}object.set("data",ordered);return json.write(object);
 }
 private LearningFailure invalid(){return LearningFailure.permanent("INVALID_EMBEDDING","向量响应与输入顺序无法对应");}
}
