package com.coursetrace.processing;

import java.util.Set;

public record ProcessingEvent(int schemaVersion,String eventId,long taskId,int pipelineVersion,
                              int runNo,String stage,int partId,int dispatchSeq) {
    public static final String TOPIC="course.processing";
    public static final String STAGE="SOURCE_CHECK";
    public ProcessingEvent(int schemaVersion,String eventId,long taskId,int pipelineVersion,int runNo,String stage,int partId){
        this(schemaVersion,eventId,taskId,pipelineVersion,runNo,stage,partId,0);
    }
    public boolean valid(){
        if(eventId==null||!eventId.matches("[a-f0-9-]{36}")||taskId<1||runNo<1||dispatchSeq<0||dispatchSeq>1000)return false;
        if(schemaVersion==1&&pipelineVersion==1)return STAGE.equals(stage)&&partId==0&&dispatchSeq==0;
        if(schemaVersion==2&&pipelineVersion==3&&stage!=null) return Set.of("CHUNK_BUILD","INDEX_PUBLISH").contains(stage)?partId==0:Set.of("EMBED_BATCH","INDEX_UPSERT").contains(stage)&&partId>=1&&partId<=200;
        return schemaVersion==2&&pipelineVersion==2&&stage!=null
            && Set.of("SOURCE_CHECK","AUDIO_EXTRACT","ASR_SUBMIT","ASR_POLL","TRANSCRIPT_BUILD","NOTE_BUILD").contains(stage)
            && ("NOTE_BUILD".equals(stage)?partId>=1&&partId<=8:partId==0);
    }
}
