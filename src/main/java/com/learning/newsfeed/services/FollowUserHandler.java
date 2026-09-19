package com.learning.newsfeed.services;

import com.learning.newsfeed.entities.Follower;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.function.BiFunction;

@Service
@RequiredArgsConstructor
public class FollowUserHandler implements BiFunction<String, String, Follower> {

    private final FollowRelationshipService relationshipService;

    @Override
    public Follower apply(String userId, String idempotencyKey) {
        return relationshipService.setFollowing(userId, idempotencyKey, true);
    }
}
