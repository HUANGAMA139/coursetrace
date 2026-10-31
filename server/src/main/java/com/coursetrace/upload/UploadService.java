package com.coursetrace.upload;
import com.coursetrace.common.ApiException;
import com.coursetrace.course.CourseService;
import com.coursetrace.media.*;
import com.coursetrace.storage.*;
import com.coursetrace.video.*;
import org.slf4j.*;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;

@Service @org.springframework.scheduling.annotation.EnableScheduling public class UploadService {
 private static final Logger log=LoggerFactory.getLogger(UploadService.class);
 private final UploadMapper uploads;private final UploadPartMapper parts;private final ObjectStorage storage;
 private final StorageProperties objects;private final MediaProperties media;private final UploadProperties config;
 private final CourseService courses;private final VideoService videos;private final MediaProbe probe;
 private final Clock clock;private final TransactionTemplate tx;
 private final Semaphore transfers=new Semaphore(4),merges=new Semaphore(1);
 public UploadService(UploadMapper uploads,UploadPartMapper parts,ObjectStorage storage,StorageProperties objects,MediaProperties media,UploadProperties config,CourseService courses,VideoService videos,MediaProbe probe,Clock clock,PlatformTransactionManager manager){
  this.uploads=uploads;this.parts=parts;this.storage=storage;this.objects=objects;this.media=media;this.config=config;this.courses=courses;this.videos=videos;this.probe=probe;this.clock=clock;tx=new TransactionTemplate(manager);
 }
 public record PartView(int partNo,long sizeBytes,String sha256){}
 public record View(String id,String status,long sizeBytes,long chunkSize,int chunkCount,List<PartView> parts,Long videoId,VideoView video,String title,String sha256,java.time.LocalDateTime expiresAt,String message,boolean canComplete){}
 public View init(long owner,long course,UploadController.Init input){
  courses.get(owner,course);
  if(input.sizeBytes()>videos.maxUploadBytes())throw error(413,"FILE_TOO_LARGE","视频超过允许的文件大小");
  if(!input.filename().toLowerCase(Locale.ROOT).endsWith(".mp4")||input.filename().contains("/")||input.filename().contains("\\")||input.title().strip().isEmpty())throw error(400,"INVALID_UPLOAD","请选择 MP4 文件及有效标题");
  if(config.getChunkBytes()<5L*1024*1024||config.getChunkBytes()>32L*1024*1024||config.getExpireHours()<1)throw new IllegalStateException("Invalid upload configuration");
  var session=tx.execute(t->{
   uploads.lockOwner(owner);
   if(uploads.lockCourse(owner,course)==null)throw notFound();
   var previous=uploads.request(owner,course,input.requestId());
   if(previous!=null){if(previous.sizeBytes!=input.sizeBytes()||!previous.fileSha256.equals(input.sha256())||!previous.filename.equals(input.filename())||!previous.title.equals(input.title().strip()))throw error(409,"UPLOAD_REQUEST_REUSED","上传编号已用于其他文件或标题");if(!Set.of("COMPLETED","CANCELLED","EXPIRED").contains(previous.status)&&!previous.expiresAt.isAfter(now())&&(previous.leaseUntil==null||!previous.leaseUntil.isAfter(now()))){previous.status="EXPIRED";previous.updatedAt=now();uploads.save(previous);}return previous;}
   var resume=uploads.resume(owner,course,input.sha256(),input.sizeBytes(),now());if(resume!=null)return resume;
   if(uploads.active(owner,now())>=config.getMaxSessions())throw error(429,"UPLOAD_LIMIT","未完成上传较多，请先继续或取消已有上传");
   var s=new UploadSession();s.id=UUID.randomUUID().toString();s.ownerId=owner;s.courseId=course;s.requestId=input.requestId();s.filename=input.filename();s.title=input.title().strip();s.fileSha256=input.sha256();s.sizeBytes=input.sizeBytes();s.chunkSize=config.getChunkBytes();s.chunkCount=Math.toIntExact((s.sizeBytes+s.chunkSize-1)/s.chunkSize);s.status="UPLOADING";s.createdAt=now();s.updatedAt=s.createdAt;s.expiresAt=s.createdAt.plusHours(config.getExpireHours());uploads.insert(s);return s;
  });return view(session);
 }
 public View status(long owner,String id){return view(owned(owner,id));}
 public View part(long owner,String id,int number,String sha,MultipartFile file){
  var session=owned(owner,id);writable(session);
  if(number<0||number>=session.chunkCount)throw error(400,"INVALID_PART","分片序号不正确");
  long expected=Math.min(session.chunkSize,session.sizeBytes-number*session.chunkSize);
  if(file.getSize()!=expected)throw error(400,"INVALID_PART_SIZE","分片大小不符合上传记录");
  if(!transfers.tryAcquire())throw error(429,"UPLOAD_BUSY","上传连接较多，请稍后继续");
  Path temp=null;String key=null;boolean adopted=false;
  try{
   temp=temporary();file.transferTo(temp);
   if(!sha.equals(hash(temp)))throw error(422,"PART_CHECKSUM_MISMATCH","分片校验失败，请重新上传该分片");
   var existing=parts.part(id,number);
   if(existing!=null){samePart(existing,sha,expected);return status(owner,id);}
   key=prefix(id)+"parts/"+number+"/"+UUID.randomUUID();
   storage.putFile(objects.bucket(),key,temp,expected,"application/octet-stream");
   String uploadedKey=key;
   adopted=Boolean.TRUE.equals(tx.execute(t->{var current=lock(owner,id);writable(current);var previous=parts.part(id,number);if(previous!=null){samePart(previous,sha,expected);return false;}var p=new UploadPart();p.uploadId=id;p.partNo=number;p.objectKey=uploadedKey;p.sha256=sha;p.sizeBytes=expected;p.createdAt=now();parts.insert(p);return true;}));
   return status(owner,id);
  }catch(ApiException e){throw e;}catch(Exception e){throw error(503,"UPLOAD_IO_ERROR","分片保存未能确认，请查询进度后继续");}
  finally{
   if(!adopted&&key!=null)try{if(parts.referenced(key)==0)storage.remove(objects.bucket(),key);}catch(Exception ignored){/* Keep uncertain objects for scheduled cleanup. */}
   delete(temp);transfers.release();
  }
 }
 public View complete(long owner,String id){
  var observed=owned(owner,id);if("COMPLETED".equals(observed.status)||("FINALIZING".equals(observed.status)&&observed.leaseUntil!=null&&observed.leaseUntil.isAfter(now())))return view(observed);
  if(!merges.tryAcquire())throw error(429,"MERGE_BUSY","正在校验其他视频，请稍后确认本次上传");
  try{
   var claim=tx.execute(t->{var s=lock(owner,id);if("COMPLETED".equals(s.status))return new CompletionClaim(s,false);
    if("FINALIZING".equals(s.status)&&s.leaseUntil!=null&&s.leaseUntil.isAfter(now()))return new CompletionClaim(s,false);
    if(!Set.of("UPLOADING","FINALIZING").contains(s.status)||!s.expiresAt.isAfter(now()))throw error(409,"UPLOAD_EXPIRED","上传已取消或过期，请重新选择文件上传");
    var all=parts.parts(id);if(all.size()!=s.chunkCount)throw error(409,"PARTS_MISSING","还有分片未完成，请先继续上传");
    for(int n=0;n<all.size();n++)if(all.get(n).partNo!=n||all.get(n).sizeBytes!=Math.min(s.chunkSize,s.sizeBytes-n*s.chunkSize))throw error(409,"PARTS_MISSING","分片记录不完整");
    s.status="FINALIZING";s.executionToken=UUID.randomUUID().toString();s.finalKey=prefix(id)+"merged/"+s.executionToken+".mp4";s.leaseUntil=now().plusMinutes(15);s.lastError=null;s.updatedAt=now();uploads.save(s);return new CompletionClaim(s,true);
   });
   return claim.acquired()?finishClaim(owner,claim.session()):view(claim.session());
  }finally{merges.release();}
 }
 private record CompletionClaim(UploadSession session,boolean acquired){}
 private View finishClaim(long owner,UploadSession claim){
  Path temp=null;
  try{
   storage.compose(objects.bucket(),parts.parts(claim.id).stream().map(p->p.objectKey).toList(),claim.finalKey);
   if(storage.size(objects.bucket(),claim.finalKey)!=claim.sizeBytes)throw error(422,"FILE_CHECKSUM_MISMATCH","合并文件大小不匹配");
   temp=temporary();try(var input=storage.open(objects.bucket(),claim.finalKey,0,claim.sizeBytes);var output=Files.newOutputStream(temp)){input.transferTo(output);}
   if(Files.size(temp)!=claim.sizeBytes||!hash(temp).equals(claim.fileSha256))throw error(422,"FILE_CHECKSUM_MISMATCH","完整文件校验失败，请取消后重新上传原文件");
   var info=probe.inspect(temp);
   var completed=tx.execute(t->{var s=lock(owner,claim.id);if(!"FINALIZING".equals(s.status)||!Objects.equals(s.executionToken,claim.executionToken))throw error(409,"UPLOAD_CHANGED","上传状态已变化，请刷新确认");
    var video=videos.saveUploaded(owner,s.courseId,s.title,s.filename,s.finalKey,s.sizeBytes,info);s.videoId=video.getId();s.status="COMPLETED";s.leaseUntil=null;s.lastError=null;s.updatedAt=now();uploads.save(s);return s;
   });return view(completed);
  }catch(Exception failure){
   try{tx.executeWithoutResult(t->{var s=uploads.lock(claim.id);if(s!=null&&"FINALIZING".equals(s.status)&&Objects.equals(s.executionToken,claim.executionToken)){s.status="UPLOADING";s.leaseUntil=null;s.lastError=failure instanceof ApiException api?api.getMessage():"合并结果未能确认，请查询状态后重试";s.updatedAt=now();uploads.save(s);}});}catch(Exception ignored){/* Leave the durable lease for manual recovery after expiry. */}
   if(failure instanceof ApiException api)throw api;
   throw error(503,"MERGE_UNAVAILABLE","合并结果未能确认，请查询状态后重试");
  }finally{delete(temp);}
 }
 public View cancel(long owner,String id){return view(tx.execute(t->{var s=lock(owner,id);if("COMPLETED".equals(s.status))return s;if("FINALIZING".equals(s.status)&&s.leaseUntil!=null&&s.leaseUntil.isAfter(now()))throw error(409,"MERGE_RUNNING","正在合并校验，请等待结果确认后再操作");s.status="CANCELLED";s.leaseUntil=null;s.updatedAt=now();uploads.save(s);return s;}));}
 @Scheduled(fixedDelayString="${coursetrace.upload.cleanup-delay-ms:60000}",initialDelayString="${coursetrace.upload.cleanup-delay-ms:60000}") public void cleanup(){
  for(var observed:uploads.cleanupDue(now(),now().minusMinutes(10)))try{
   var s=tx.execute(t->{var current=uploads.lock(observed.id);if(current==null||(current.leaseUntil!=null&&current.leaseUntil.isAfter(now())))return null;if(!Set.of("COMPLETED","CANCELLED","EXPIRED").contains(current.status)){if(current.expiresAt.isAfter(now()))return null;current.status="EXPIRED";current.updatedAt=now();uploads.save(current);}return current;});
   if(s==null)continue;
   for(var key:storage.list(objects.bucket(),prefix(s.id)))if(!("COMPLETED".equals(s.status)&&key.equals(s.finalKey)))storage.remove(objects.bucket(),key);
   uploads.cleaned(s.id,now());
  }catch(Exception e){log.warn("Upload cleanup deferred: upload={}, type={}",observed.id,e.getClass().getSimpleName());}
 }
 private View view(UploadSession s){var rows=parts.parts(s.id).stream().map(p->new PartView(p.partNo,p.sizeBytes,p.sha256)).toList();VideoView video=s.videoId==null?null:VideoView.from(videos.owned(s.ownerId,s.videoId));boolean canComplete=s.expiresAt.isAfter(now())&&("UPLOADING".equals(s.status)||("FINALIZING".equals(s.status)&&(s.leaseUntil==null||!s.leaseUntil.isAfter(now()))));return new View(s.id,s.status,s.sizeBytes,s.chunkSize,s.chunkCount,rows,s.videoId,video,s.title,s.fileSha256,s.expiresAt,s.lastError,canComplete);}
 private UploadSession owned(long owner,String id){var s=uploads.selectById(id);if(s==null||s.ownerId!=owner)throw notFound();return s;}
 private UploadSession lock(long owner,String id){var s=uploads.lock(id);if(s==null||s.ownerId!=owner)throw notFound();return s;}
 private void writable(UploadSession s){if(!"UPLOADING".equals(s.status)||!s.expiresAt.isAfter(now()))throw error(409,"UPLOAD_NOT_WRITABLE","当前上传不可写，请查询状态或重新初始化");}
 private void samePart(UploadPart p,String sha,long size){if(!p.sha256.equals(sha)||p.sizeBytes!=size)throw error(409,"PART_CONFLICT","该分片已确认且内容不同，请重新选择原文件");}
 private Path temporary()throws java.io.IOException{var root=Path.of(media.tempDirectory()).toAbsolutePath().normalize();Files.createDirectories(root);return Files.createTempFile(root,"upload-",".bin");}
 private void delete(Path file){if(file!=null)try{Files.deleteIfExists(file);}catch(Exception ignored){}}
 public static String hash(Path file)throws Exception{var digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(file)){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);}return HexFormat.of().formatHex(digest.digest());}
 private String prefix(String id){return "uploads/"+id+"/";}
 private LocalDateTime now(){return LocalDateTime.now(clock);}
 private ApiException notFound(){return error(404,"UPLOAD_NOT_FOUND","上传不存在或无法访问");}
 private ApiException error(int status,String code,String message){return new ApiException(HttpStatus.valueOf(status),code,message);}
}
