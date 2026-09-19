package com.learning.newsfeed.controllers;

import com.learning.newsfeed.annotation.RequireScope;
import com.learning.newsfeed.configurations.dynamodb.DynamoPaginator;
import com.learning.newsfeed.dto.req.CreatePostDTO;
import com.learning.newsfeed.entities.Scopes;
import com.learning.newsfeed.services.CreatePostHandler;
import com.learning.newsfeed.services.FeedNewsHandler;
import com.learning.newsfeed.services.FollowUserHandler;
import com.learning.newsfeed.services.GetUserPostsHandler;
import com.learning.newsfeed.services.UnfollowUserHandler;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/newsfeed")
public class NewsFeedController {

    private final CreatePostHandler createPostHandler;
    private final FeedNewsHandler feedNewsHandler;
    private final GetUserPostsHandler getUserPostsHandler;
    private final FollowUserHandler followUserHandler;
    private final UnfollowUserHandler unfollowUserHandler;

    @RequireScope(Scopes.Posts.WRITE)
    @PostMapping("/posts")
    public ResponseEntity<?> createPost(
            @RequestHeader("idempotency-key") String idempotencyKey,
            @Valid @RequestBody CreatePostDTO dto,
            @AuthenticationPrincipal Jwt jwt
    ) {
        dto.setUserId(jwt.getSubject());
        dto.setIdempotencyKey(idempotencyKey);
        createPostHandler.accept(dto);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @RequireScope(Scopes.Posts.READ)
    @GetMapping("feed")
    public ResponseEntity<?> getFeed(
            @RequestParam(name = "size", defaultValue = "10") int size,
            @RequestParam(name = "cursor", required = false) String cursor
    ) {
        return ResponseEntity.ok().body(
                feedNewsHandler.apply(
                    DynamoPaginator.builder()
                            .size(size)
                            .build()
                )
        );
    }

    @RequireScope(Scopes.Posts.READ)
    @GetMapping("/users/{userId}/posts")
    public ResponseEntity<?> getUserPosts(
            @PathVariable("userId") String userId,
            @RequestParam(name = "size", defaultValue = "10") int size,
            @RequestParam(name = "cursor", required = false) String cursor
    ) {
        return ResponseEntity.ok().body(
                getUserPostsHandler.handle(
                        userId,
                        DynamoPaginator.builder()
                                .size(size)
                                .build()
                )
        );
    }

    @RequireScope(Scopes.Follows.WRITE)
    @PostMapping("/users/{userId}/follow")
    public ResponseEntity<?> followUser(@PathVariable("userId") String userId) {
        return ResponseEntity.ok().body(followUserHandler.apply(userId));
    }

    @RequireScope(Scopes.Follows.WRITE)
    @PostMapping("/users/{userId}/unfollow")
    public ResponseEntity<?> unfollowUser(@PathVariable("userId") String userId) {
        return ResponseEntity.ok().body(unfollowUserHandler.apply(userId));
    }
}
