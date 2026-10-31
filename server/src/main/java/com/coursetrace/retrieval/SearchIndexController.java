package com.coursetrace.retrieval;
import com.coursetrace.auth.AuthenticatedUser;
import com.coursetrace.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
@RestController @Validated public class SearchIndexController {
 private final SearchIndexService indexes;
 public SearchIndexController(SearchIndexService indexes){this.indexes=indexes;}
 @GetMapping("/api/videos/{videoId}/search-index")
 ApiResponse<?> status(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable @Min(1)long videoId){return ApiResponse.ok(indexes.status(user.id(),videoId));}
 @PostMapping("/api/videos/{videoId}/search-index")
 ResponseEntity<?> submit(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable @Min(1)long videoId){return ResponseEntity.accepted().body(ApiResponse.ok(indexes.submit(user.id(),videoId)));}
 @PostMapping("/api/videos/{videoId}/search-index/rebuild")
 ResponseEntity<?> rebuild(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable @Min(1)long videoId,@RequestBody @Valid Rebuild request){
  return ResponseEntity.accepted().body(ApiResponse.ok(indexes.rebuild(user.id(),videoId,request.expectedIndexId(),request.expectedRunNo(),request.requestId())));
 }
 public record Rebuild(@Min(1)long expectedIndexId,@Min(1)int expectedRunNo,@NotBlank @Pattern(regexp="[a-zA-Z0-9-]{16,64}")String requestId){}
}
