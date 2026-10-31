package com.coursetrace.agent;
import com.coursetrace.retrieval.RetrievalService.Evidence;
import java.util.*;
/** Only continuous, actually returned ranges can become final evidence. */
public final class EvidenceLedger {
 private final Map<Long,List<Evidence>> ranges=new LinkedHashMap<>();
 public boolean contains(long id){return ranges.containsKey(id);}
 public int add(List<Evidence> incoming){
  int before=coverage();
  for(var e:incoming){
   var list=ranges.computeIfAbsent(e.segmentId(),id->new ArrayList<>());
   Evidence merged=e;
   for(var it=list.iterator();it.hasNext();){
    var old=it.next();
    if(merged.startOffset()<=old.endOffset()&&old.startOffset()<=merged.endOffset()){
     int start=Math.min(old.startOffset(),merged.startOffset()),end=Math.max(old.endOffset(),merged.endOffset());
     char[] chars=new char[end-start];
     old.text().getChars(0,old.text().length(),chars,old.startOffset()-start);
     merged.text().getChars(0,merged.text().length(),chars,merged.startOffset()-start);
     merged=new Evidence(e.segmentId(),start,end,new String(chars),e.beginMs(),e.endMs());it.remove();
    }
   }
   list.add(merged);
  }
  return coverage()-before;
 }
 private int coverage(){return ranges.values().stream().flatMap(List::stream).mapToInt(e->e.text().length()).sum();}
 public List<Evidence> finalEvidence(){
  var result=new ArrayList<Evidence>();int remaining=8000;
  for(var list:ranges.values()){
   var e=list.stream().max(Comparator.comparingInt(v->v.text().length())).orElseThrow();
   if(result.size()>=80||remaining<=0)break;
   int size=Math.min(e.text().length(),remaining);
   if(size<e.text().length()&&Character.isHighSurrogate(e.text().charAt(size-1)))size--;
   if(size>0){result.add(new Evidence(e.segmentId(),e.startOffset(),e.startOffset()+size,e.text().substring(0,size),e.beginMs(),e.endMs()));remaining-=size;}
  }
  return List.copyOf(result);
 }
}
