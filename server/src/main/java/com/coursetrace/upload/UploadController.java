package com.coursetrace.upload;
import com.coursetrace.auth.AuthenticatedUser;
import com.coursetrace.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
@RestController @Validated public class UploadController {
 private final UploadService uploads;
 public UploadController(UploadService uploads){this.uploads=uploads;}
 @PostMapping("/api/courses/{courseId}/uploads")
 Object init(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable @Min(1)long courseId,@RequestBody @Valid Init request){return ApiResponse.ok(uploads.init(user.id(),courseId,request));}
 @GetMapping("/api/uploads/{id}") Object status(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable String id){return ApiResponse.ok(uploads.status(user.id(),id));}
 @PutMapping(value="/api/uploads/{id}/parts/{part}",consumes="multipart/form-data")
 Object part(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable String id,@PathVariable @Min(0)int part,@RequestParam @Pattern(regexp="[a-f0-9]{64}")String sha256,@RequestPart MultipartFile file){return ApiResponse.ok(uploads.part(user.id(),id,part,sha256,file));}
 @PostMapping("/api/uploads/{id}/complete") Object complete(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable String id){var view=uploads.complete(user.id(),id);return ResponseEntity.status(view.status().equals("FINALIZING")?202:200).body(ApiResponse.ok(view));}
 @DeleteMapping("/api/uploads/{id}") Object cancel(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable String id){return ApiResponse.ok(uploads.cancel(user.id(),id));}
 public record Init(@NotBlank @Pattern(regexp="[a-zA-Z0-9-]{16,64}")String requestId,@NotBlank @Size(max=255)String filename,@NotBlank @Size(max=120)String title,@Min(1)long sizeBytes,@NotBlank @Pattern(regexp="[a-f0-9]{64}")String sha256){}
}
