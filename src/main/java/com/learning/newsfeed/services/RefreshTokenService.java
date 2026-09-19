package com.learning.newsfeed.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RefreshTokenService {

    private static final Duration SESSION_TTL = Duration.ofDays(7);
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-"
                    + "[0-9a-f]{12}\\.[A-Za-z0-9_-]{43}$"
    );

    private static final DefaultRedisScript<Long> ISSUE_SCRIPT =
            new DefaultRedisScript<>("""
                if redis.call('EXISTS', KEYS[1]) == 1 then
                    return 0
                end

                redis.call('HSET', KEYS[1],
                    'userId', ARGV[1],
                    'currentHash', ARGV[2])

                redis.call('EXPIRE', KEYS[1], ARGV[3])
                return 1
                """, Long.class);

    private static final DefaultRedisScript<String> ROTATE_SCRIPT =
            new DefaultRedisScript<>("""
                local current = redis.call(
                    'HGET', KEYS[1], 'currentHash'
                )

                if not current then
                    return 'INVALID'
                end

                if current == ARGV[1] then
                    local userId = redis.call(
                        'HGET', KEYS[1], 'userId'
                    )

                    redis.call('HSET', KEYS[1],
                        'used:' .. ARGV[1], '1',
                        'currentHash', ARGV[2])

                    return 'OK:' .. userId
                end

                if redis.call(
                    'HEXISTS', KEYS[1], 'used:' .. ARGV[1]
                ) == 1 then
                    redis.call('DEL', KEYS[1])
                    return 'REUSED'
                end

                return 'INVALID'
                """, String.class);

    private static final DefaultRedisScript<Long> REVOKE_SCRIPT =
            new DefaultRedisScript<>("""
                local current = redis.call(
                    'HGET', KEYS[1], 'currentHash'
                )

                if current == ARGV[1]
                    or redis.call(
                        'HEXISTS', KEYS[1], 'used:' .. ARGV[1]
                    ) == 1 then
                    return redis.call('DEL', KEYS[1])
                end

                return 0
                """, Long.class);

    private final StringRedisTemplate redis;

    public RefreshTokenService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public String issue(String userId) {
        Objects.requireNonNull(userId, "userId");

        String sessionId = UUID.randomUUID().toString();
        String token = newToken(sessionId);

        Long created = redis.execute(
                ISSUE_SCRIPT,
                List.of(redisKey(sessionId)),
                userId,
                sha256(token),
                Long.toString(SESSION_TTL.toSeconds())
        );

        if (!Long.valueOf(1).equals(created)) {
            throw new IllegalStateException(
                    "Could not create refresh session"
            );
        }

        return token;
    }

    public RotatedToken rotate(String rawToken) {
        String sessionId = sessionIdOf(rawToken);
        String newToken = newToken(sessionId);

        String result = redis.execute(
                ROTATE_SCRIPT,
                List.of(redisKey(sessionId)),
                sha256(rawToken),
                sha256(newToken)
        );

        if (result == null || !result.startsWith("OK:")) {
            throw invalidToken();
        }

        return new RotatedToken(
                result.substring("OK:".length()),
                newToken
        );
    }

    public void revoke(String rawToken) {
        String sessionId = sessionIdOf(rawToken);

        redis.execute(
                REVOKE_SCRIPT,
                List.of(redisKey(sessionId)),
                sha256(rawToken)
        );
    }

    private static String newToken(String sessionId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);

        String secret = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);

        return sessionId + "." + secret;
    }

    private static String sessionIdOf(String token) {
        if (token == null || !TOKEN_PATTERN.matcher(token).matches()) {
            throw invalidToken();
        }

        return token.substring(0, token.indexOf('.'));
    }

    private static String redisKey(String sessionId) {
        return "auth:refresh:" + sessionId;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResponseStatusException invalidToken() {
        return new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Invalid or expired refresh token"
        );
    }

    public record RotatedToken(
            String userId,
            String refreshToken
    ) {}
}