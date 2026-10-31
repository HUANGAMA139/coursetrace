package com.coursetrace.processing;

import com.coursetrace.auth.AuthenticatedUser;
import com.coursetrace.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.List;

@RestController @Validated
public class ProcessingController {
    private final ProcessingService processing;
    public ProcessingController(ProcessingService processing){this.processing=processing;}
    @PostMapping("/api/videos/{videoId}/processing-tasks")
    ResponseEntity<?> submit(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable @Min(1) long videoId,
                            @RequestBody(required=false) @Valid SubmitRequest request){
        var result=processing.submit(user.id(),videoId,request==null?1:request.pipelineVersion());
        return ResponseEntity.status(result.created()?202:200).location(URI.create("/api/processing-tasks/"+result.task().id())).body(ApiResponse.ok(result.task()));
    }
    @GetMapping("/api/processing-tasks/{taskId}")
    ApiResponse<?> get(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable @Min(1) long taskId){return ApiResponse.ok(processing.get(user.id(),taskId));}
    @GetMapping("/api/courses/{courseId}/processing-tasks")
    ApiResponse<?> batch(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable @Min(1) long courseId,
        @RequestParam(defaultValue="") List<Long> videoIds,@RequestParam(defaultValue="1") @Min(1) @Max(3) int pipelineVersion){
        return ApiResponse.ok(processing.batch(user.id(),courseId,videoIds,pipelineVersion));
    }
    @PostMapping("/api/processing-tasks/{taskId}/retry")
    ResponseEntity<?> retry(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable @Min(1) long taskId,@RequestBody @Valid RetryRequest request){
        return ResponseEntity.accepted().body(ApiResponse.ok(processing.retry(user.id(),taskId,request.expectedRunNo(),request.confirmResubmitUncertain())));
    }
    public record SubmitRequest(@Min(1) @Max(3) int pipelineVersion){}
    public record RetryRequest(@Min(1) int expectedRunNo,boolean confirmResubmitUncertain){}
}
