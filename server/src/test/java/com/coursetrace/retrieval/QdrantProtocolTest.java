package com.coursetrace.retrieval;
import com.coursetrace.learning.*;
import com.coursetrace.retrieval.SearchModels.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;

class QdrantProtocolTest {
 private HttpServer server;private QdrantIndexClient client;private ObjectMapper mapper=new ObjectMapper();private LearningJson json=new LearningJson(mapper);
 private RetrievalProperties properties;private List<JsonNode> requests=new CopyOnWriteArrayList<>();private int size=1024;private boolean completed=true,missing=false,disconnectQuery=false;
 @BeforeEach void setup()throws Exception{
  server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{
   String path=exchange.getRequestURI().getPath(),body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8),response;
   if(!body.isEmpty())requests.add(json.read(body));
   if(path.endsWith("/points/query")&&disconnectQuery){disconnectQuery=false;exchange.close();return;}
   if(path.endsWith("/embeddings"))response="{\"data\":[{\"index\":1,\"embedding\":[0.0,1.0]},{\"index\":0,\"embedding\":[1.0,0.0]}],\"usage\":{\"prompt_tokens\":2,\"total_tokens\":2},\"model\":\"fixture\"}";
   else if(path.endsWith("/points/query"))response=json.write(Map.of("result",Map.of("points",List.of(Map.of("id",chunk().pointId,"score",0.9,"payload",client.payload(index(),chunk(),config()))))));
   else if(path.endsWith("/points/count"))response="{\"result\":{\"count\":0}}";
   else if(path.endsWith("/points"))response="{\"result\":{\"status\":\""+(completed?"completed":"acknowledged")+"\"}}";
   else if(path.endsWith("/readyz"))response="ready";
   else if(path.endsWith("/index"))response="{\"result\":{\"status\":\"completed\"}}";
   else response="{\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":"+size+",\"distance\":\"Cosine\"}}}}}";
   byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
   exchange.sendResponseHeaders(missing?404:200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
  });server.start();properties=new RetrievalProperties();properties.setQdrantUrl("http://127.0.0.1:"+server.getAddress().getPort());client=new QdrantIndexClient(properties,json);
 }
 @AfterEach void stop(){server.stop(0);}
 RetrievalProperties.Options config(){return properties.snapshot(new LearningProperties());}
 SearchIndex index(){var i=new SearchIndex();i.id=1L;i.ownerId=2L;i.courseId=3L;i.videoId=4L;i.transcriptArtifactId=5L;i.collectionName=config().collection();return i;}
 Chunk chunk(){var c=new Chunk();c.id=6L;c.indexId=1L;c.pointId="00000000-0000-0000-0000-000000000006";c.contentHash=LearningJson.hash("fixture");return c;}
 @Test void actualRestBodiesContainAllScopeFiltersAndPayloadIndexes(){
  client.ensure(config());float[] v=new float[1024];v[0]=1;client.upsert(index(),List.of(chunk()),List.of(v),config());
  var hits=client.query(index(),v,config());assertThat(hits).hasSize(1);assertThat(client.matches(hits.getFirst().payload(),index(),chunk(),config())).isTrue();
  assertThat(requests.stream().filter(r->r.has("field_name")).count()).isEqualTo(6);
  var query=requests.getLast();assertThat(query.path("with_vector").asBoolean()).isFalse();
  var fields=new HashSet<String>();for(var clause:query.path("filter").path("must"))fields.add(clause.path("key").asText());
  assertThat(fields).containsExactlyInAnyOrder("owner_id","course_id","video_id","transcript_artifact_id","index_id");
 }
 @Test void incompatibleCollectionNeverDeletesOrOverwritesIt(){
  size=512;assertThatThrownBy(()->client.ensure(config())).isInstanceOf(LearningFailure.class).extracting(e->((LearningFailure)e).code).isEqualTo("INDEX_SCHEMA_MISMATCH");assertThat(requests).isEmpty();
 }
 @Test void acknowledgementAloneIsNotACompletedWrite(){
  completed=false;float[] v=new float[1024];v[0]=1;
  assertThatThrownBy(()->client.upsert(index(),List.of(chunk()),List.of(v),config())).isInstanceOf(LearningFailure.class).extracting(e->((LearningFailure)e).retryable).isEqualTo(true);
  assertThat(client.complete(index(),List.of(chunk()),config())).isFalse();
 }
 @Test void missingCollectionIsExplicitAndNotAutomaticallyRecreatedByQuery(){
  missing=true;float[] v=new float[1024];v[0]=1;
  assertThatThrownBy(()->client.query(index(),v,config())).isInstanceOf(LearningFailure.class).extracting(e->((LearningFailure)e).code).isEqualTo("INDEX_MISSING");
 }
 @Test void langChainAdapterActuallyUsesValidatedResponseIndexOrder(){
  var model=OpenAiEmbeddingModel.builder().baseUrl(properties.getQdrantUrl()+"/v1").apiKey("test-placeholder").modelName("fixture").dimensions(2).maxRetries(0).httpClientBuilder(new OrderedEmbeddingHttpClient(json)).build();
  var result=model.embedAll(List.of(TextSegment.from("first"),TextSegment.from("second"))).content();
  assertThat(result.get(0).vector()).containsExactly(1,0);assertThat(result.get(1).vector()).containsExactly(0,1);
 }
 @Test void droppedReusableConnectionCanRecoverWithoutDuplicatingPoints(){
  assertThat(client.available()).isTrue();disconnectQuery=true;float[] vector=new float[1024];vector[0]=1;
  assertThat(client.query(index(),vector,config())).hasSize(1);
  assertThat(requests.stream().filter(r->r.has("query")).count()).isEqualTo(2);
 }}
