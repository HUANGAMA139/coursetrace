package com.coursetrace.retrieval;
import com.coursetrace.learning.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class SubtitleChunkerTest {
 private final SubtitleChunker chunker=new SubtitleChunker();
 private RetrievalProperties.Options config(){var p=new RetrievalProperties();p.setTargetChars(100);p.setMaxChars(120);p.setOverlapChars(30);return p.snapshot(new LearningProperties());}
 private Segment segment(long id,int ordinal,String text){var s=new Segment();s.id=id;s.videoId=1L;s.transcriptArtifactId=2L;s.ordinal=ordinal;s.beginMs=ordinal*1000L;s.endMs=s.beginMs+900;s.text=text;return s;}
 @Test void longUnicodeSentencesPreserveEverySourceOffsetAndOriginalTime(){
  var s=segment(1,0,"数组🙂有序".repeat(150));var chunks=chunker.split(List.of(s),config());
  assertThat(chunks).hasSizeGreaterThan(3);String joined=String.join("",chunks.stream().map(SubtitleChunker.Draft::text).toList());assertThat(joined).isEqualTo(s.text);
  int cursor=0;for(var c:chunks){assertThat(c.text().codePointCount(0,c.text().length())).isLessThanOrEqualTo(120);assertThat(c.beginMs()).isEqualTo(s.beginMs);assertThat(c.endMs()).isEqualTo(s.endMs);
   var source=c.sources().getFirst();assertThat(source.startOffset()).isEqualTo(cursor);assertThat(s.text.substring(source.startOffset(),source.endOffset())).isEqualTo(c.text());cursor=source.endOffset();}
 }
 @Test void shortSentencesOverlapWithoutLoopAndAllSourcesRemain(){
  var segments=new ArrayList<Segment>();for(int n=0;n<30;n++)segments.add(segment(n+1,n,"句子"+n+"。".repeat(12)));
  var first=chunker.split(segments,config());var second=chunker.split(segments,config());assertThat(first).isEqualTo(second);
  assertThat(first.stream().flatMap(c->c.sources().stream()).map(SubtitleChunker.Source::segmentId).distinct().count()).isEqualTo(30);
  assertThat(first.size()).isLessThan(30);assertThat(first.stream().mapToInt(c->c.sources().size()).sum()).isGreaterThan(30);
 }
 @Test void emptyUnorderedAndExcessiveInputFailBeforeModelCalls(){
  assertThatThrownBy(()->chunker.split(List.of(),config())).isInstanceOf(LearningFailure.class);
  assertThatThrownBy(()->chunker.split(List.of(segment(1,2,"a"),segment(2,1,"b")),config())).isInstanceOf(LearningFailure.class);
  var p=new RetrievalProperties();p.setMaxChunks(1);assertThatThrownBy(()->chunker.split(List.of(segment(1,0,"课程".repeat(3000))),p.snapshot(new LearningProperties()))).isInstanceOf(LearningFailure.class);
 }
 @Test void vectorsRejectWrongCountDimensionNanAndZero(){
  assertThatThrownBy(()->QwenEmbeddingClient.validate(List.of(new float[1024]),1,1024)).isInstanceOf(LearningFailure.class);
  assertThatThrownBy(()->QwenEmbeddingClient.validate(List.of(new float[]{1}),1,1024)).isInstanceOf(LearningFailure.class);
  float[] vector=new float[1024];vector[0]=Float.NaN;assertThatThrownBy(()->QwenEmbeddingClient.validate(List.of(vector),1,1024)).isInstanceOf(LearningFailure.class);
  vector[0]=1;QwenEmbeddingClient.validate(List.of(vector),1,1024);
  assertThatThrownBy(()->QwenEmbeddingClient.validate(List.of(vector),2,1024)).isInstanceOf(LearningFailure.class);
 }
 @Test void indexEventsKeepLegacyVersionsAndRejectInvalidParts(){
  String id=UUID.randomUUID().toString();
  assertThat(new com.coursetrace.processing.ProcessingEvent(2,id,1,3,1,"EMBED_BATCH",1,0).valid()).isTrue();
  assertThat(new com.coursetrace.processing.ProcessingEvent(2,id,1,3,1,"CHUNK_BUILD",1,0).valid()).isFalse();
  assertThat(new com.coursetrace.processing.ProcessingEvent(2,id,1,3,1,"EMBED_BATCH",201,0).valid()).isFalse();
  assertThat(new com.coursetrace.processing.ProcessingEvent(1,id,1,1,1,"SOURCE_CHECK",0).valid()).isTrue();
 }
}
