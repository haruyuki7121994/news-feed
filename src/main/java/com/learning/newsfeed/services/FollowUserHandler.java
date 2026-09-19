package com.learning.newsfeed.services;

import com.learning.newsfeed.entities.Follower;
import com.learning.newsfeed.repositories.AuthenticationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.function.BiFunction;

@Service
@RequiredArgsConstructor
public class FollowUserHandler implements BiFunction<String, String, Follower> {

    private final FollowRelationshipService relationshipService;
    private final AuthenticationRepository authenticationRepository;

    @Override
    public Follower apply(String userId, String idempotencyKey) {
        Authentication authentication = authenticationRepository.getAuthContext()
                .filter(Authentication::isAuthenticated)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return relationshipService.setFollowing(authentication.getName(), userId, idempotencyKey, true);
    }

    public Follower apply(String userId, String followingId, String idempotencyKey) {
        return relationshipService.setFollowing(userId, followingId, idempotencyKey, true);
    }
}
