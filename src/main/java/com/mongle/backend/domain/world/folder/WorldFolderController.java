package com.mongle.backend.domain.world.folder;

import com.mongle.backend.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

@RestController
@RequiredArgsConstructor
@RequestMapping(value = "/api/v1/worlds", produces = MediaType.APPLICATION_JSON_VALUE)
public class WorldFolderController implements WorldFolderApi {
    private final WorldFolderService folders;

    @Override @PostMapping
    public ResponseEntity<ApiResponse<WorldFolderResponse>> create(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody WorldFolderRequest request) {
        var folder = folders.create(userId(jwt), request.name());
        return ResponseEntity.created(URI.create("/api/v1/worlds/" + folder.worldId()))
                .cacheControl(CacheControl.noStore()).body(ApiResponse.success(folder));
    }

    @Override @GetMapping
    public ResponseEntity<ApiResponse<WorldFolderPage>> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Long beforeId, @RequestParam(defaultValue = "20") int size) {
        return ok(folders.list(userId(jwt), beforeId, size));
    }

    @Override @GetMapping("/{worldId}")
    public ResponseEntity<ApiResponse<WorldFolderResponse>> get(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive Long worldId) {
        return ok(folders.get(userId(jwt), worldId));
    }

    @Override @PatchMapping("/{worldId}")
    public ResponseEntity<ApiResponse<WorldFolderResponse>> rename(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive Long worldId, @Valid @RequestBody WorldFolderUpdateRequest request) {
        return ok(folders.rename(userId(jwt), worldId, request));
    }





    private Long userId(Jwt jwt) { return Long.valueOf(jwt.getSubject()); }

    private <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(data));
    }
}
