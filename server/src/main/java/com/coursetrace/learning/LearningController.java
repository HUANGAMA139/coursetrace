package com.coursetrace.learning;

import com.coursetrace.auth.AuthenticatedUser;
import com.coursetrace.common.ApiResponse;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @Validated
public class LearningController {
    private final LearningReadService read;
    private final LearningWorkflow workflow;
    public LearningController(LearningReadService read,LearningWorkflow workflow){this.read=read;this.workflow=workflow;}
    @GetMapping("/api/learning-capabilities")
    ApiResponse<?> capabilities(){return ApiResponse.ok(workflow.capabilities());}
    @GetMapping("/api/videos/{id}/transcript")
    ApiResponse<?> transcript(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable @Min(1) long id){return ApiResponse.ok(read.transcript(user.id(),id));}
    @GetMapping("/api/videos/{id}/segments")
    ApiResponse<?> segments(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable @Min(1) long id,
        @RequestParam(defaultValue="1") @Min(1) @Max(100000) int page,@RequestParam(defaultValue="50") @Min(1) @Max(100) int size,
        @RequestParam(required=false) @Min(0) Long atMs){return ApiResponse.ok(read.segments(user.id(),id,page,size,atMs));}
    @GetMapping("/api/videos/{id}/notes")
    ApiResponse<?> notes(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable @Min(1) long id){return ApiResponse.ok(read.notes(user.id(),id));}
    @GetMapping("/api/videos/{id}/subtitles.vtt")
    ResponseEntity<String> vtt(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable @Min(1) long id,
        @RequestParam(required=false) @Min(1) Long version){
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/vtt;charset=UTF-8")).cacheControl(CacheControl.noStore().cachePrivate()).body(read.vtt(user.id(),id,version));
    }
}
