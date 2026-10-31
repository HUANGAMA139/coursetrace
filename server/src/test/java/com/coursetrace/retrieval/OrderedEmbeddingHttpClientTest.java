package com.coursetrace.retrieval;
import com.coursetrace.learning.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class OrderedEmbeddingHttpClientTest {
 private final LearningJson json=new LearningJson(new ObjectMapper());private final OrderedEmbeddingHttpClient client=new OrderedEmbeddingHttpClient(json);
 @Test void responseIndexesRestoreOriginalInputOrder(){
  var result=json.read(client.order("{\"input\":[\"first\",\"second\"]}","{\"data\":[{\"index\":1,\"embedding\":[2]},{\"index\":0,\"embedding\":[1]}],\"usage\":{\"total_tokens\":2}}"));
  assertThat(result.path("data").get(0).path("embedding").get(0).asInt()).isEqualTo(1);
  assertThat(result.path("usage").path("total_tokens").asInt()).isEqualTo(2);
 }
 @Test void duplicateMissingAndOutOfRangeIndexesAreRejected(){
  for(String response:new String[]{"{\"data\":[{\"index\":0},{\"index\":0}]}","{\"data\":[{\"index\":0}]}","{\"data\":[{\"index\":0},{\"index\":2}]}","{\"data\":[{\"embedding\":[1]},{\"index\":1}]}"}){
   assertThatThrownBy(()->client.order("{\"input\":[\"a\",\"b\"]}",response)).isInstanceOf(LearningFailure.class);
  }
 }
}
