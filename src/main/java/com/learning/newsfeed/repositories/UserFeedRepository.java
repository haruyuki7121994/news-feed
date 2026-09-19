package com.learning.newsfeed.repositories;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class UserFeedRepository {

    private final StringRedisTemplate redisTemplate;

    public void addToLatestFeed(
            String userId,
            String postId,
            LocalDateTime createdAt
    ) {

        String key = String.format("userFeeds:%s:latest", userId);
        redisTemplate.opsForZSet().add(
                key,
                postId,
                createdAt.atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
        );
    }

    public void updateRanking(
            String userId,
            String postId,
            double score
    ) {
        String key = String.format("userFeeds:%s:ranked", userId);
        redisTemplate.opsForZSet().add(key, postId, score);
    }

    public Set<String> getLatestFeed(String userId, int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive");
        }

        String key = String.format("userFeeds:%s:latest", userId);

        Set<String> result = redisTemplate.opsForZSet()
                .reverseRange(key, 0, size - 1L);

        return result == null ? Collections.emptySet() : result;
    }

    public void removePost(String userId, String postId) {
        redisTemplate.opsForZSet().remove(String.format("userFeeds:%s:latest", userId), postId);
        redisTemplate.opsForZSet().remove(String.format("userFeeds:%s:ranked", userId), postId);
    }
}
