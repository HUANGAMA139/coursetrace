package com.coursetrace.retrieval;
import com.coursetrace.auth.AuthenticatedUser;
import com.coursetrace.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
@RestController @Validated public class CourseQaController {
 private final CourseQaService questions;
 public CourseQaController(CourseQaService questions){this.questions=questions;}
 @PostMapping("/api/videos/{videoId}/questions")
 ResponseEntity<?> ask(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable @Min(1)long videoId,@RequestBody @Valid Question request){
  var result=questions.ask(user.id(),videoId,request.requestId(),request.question(),request.mode());return ResponseEntity.status("RUNNING".equals(result.get("status"))?202:200).body(ApiResponse.ok(result));
 }
 @GetMapping("/api/videos/{videoId}/questions")
 ApiResponse<?> history(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable @Min(1)long videoId,@RequestParam(defaultValue="1")@Min(1)@Max(100000)int page,@RequestParam(defaultValue="20")@Min(1)@Max(50)int size){
  return ApiResponse.ok(questions.history(user.id(),videoId,page,size));
 }
 @GetMapping("/api/videos/{videoId}/questions/{turnId}")
 ApiResponse<?> get(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable @Min(1)long videoId,@PathVariable @Min(1)long turnId){return ApiResponse.ok(questions.get(user.id(),videoId,turnId));}
 @GetMapping("/api/videos/{videoId}/questions/request/{requestId}")
 ApiResponse<?> request(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable @Min(1)long videoId,
  @PathVariable @Pattern(regexp="[a-zA-Z0-9-]{16,64}")String requestId){return ApiResponse.ok(questions.request(user.id(),videoId,requestId));}
 @GetMapping("/api/videos/{videoId}/questions/{turnId}/agent-run")
 ApiResponse<?> agentRun(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable @Min(1)long videoId,@PathVariable @Min(1)long turnId){return ApiResponse.ok(questions.agentRun(user.id(),videoId,turnId));}
 public record Question(@NotBlank @Pattern(regexp="[a-zA-Z0-9-]{16,64}")String requestId,@NotBlank @Size(max=1000)String question,@Pattern(regexp="RAG|AGENT")String mode){}
}
