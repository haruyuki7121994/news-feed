package com.learning.newsfeed.services;

import com.learning.newsfeed.entities.Follower;
import com.learning.newsfeed.entities.IdempotencyKey;
import com.learning.newsfeed.entities.User;
import com.learning.newsfeed.repositories.AuthenticationRepository;
import com.learning.newsfeed.repositories.FollowerRepository;
import com.learning.newsfeed.repositories.IdempotencyKeyRepository;
import com.learning.newsfeed.repositories.UserRepository;
import com.learning.newsfeed.utils.MapperUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.model.GetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactPutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactWriteItemsEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.function.BiFunction;

@Slf4j
@Service
@RequiredArgsConstructor
public class UnfollowUserHandler implements BiFunction<String, String, Follower> {

    private final UserRepository userRepository;
    private final FollowerRepository followerRepository;
    private final AuthenticationRepository authenticationRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final DynamoDbEnhancedClient enhancedClient;

    @Override
    public Follower apply(String userId, String idemKey) {
        Authentication authContext = authenticationRepository.getAuthContext().orElseThrow();
        User loggedInUser = userRepository.query(authContext.getName()).orElseThrow();
        User userToFollow = userRepository.query(userId).orElseThrow();
        userToFollow.setFollowersCount(userToFollow.getFollowersCount() - 1);

        Follower follower = followerRepository.query(
                Follower.FOLLOWER_PREFIX + loggedInUser.getUserId(),
                Follower.FOLLOWER_PREFIX + userToFollow.getUserId()
        ).orElseThrow();

        String requestHash = requestHash(follower);
        IdempotencyKey idempotencyKey = createIdempotency(follower, requestHash, idemKey);
        TransactPutItemEnhancedRequest<IdempotencyKey> putRequestWithCondition = TransactPutItemEnhancedRequest.builder(IdempotencyKey.class)
                .item(idempotencyKey)
                .conditionExpression(
                        Expression.builder()
                                .expression("attribute_not_exists(PK)")
                                .build()
                )
                .build();

        try {
            // Build the atomic transaction package
            TransactWriteItemsEnhancedRequest transactionRequest = TransactWriteItemsEnhancedRequest.builder()
                    .addDeleteItem(followerRepository.table(), follower)
                    .addPutItem(idempotencyKeyRepository.table(), putRequestWithCondition)
                    .addPutItem(userRepository.table(), userToFollow)
                    .build();

            // Execute the batch call. If any operation fails, the entire payload is rejected.
            enhancedClient.transactWriteItems(transactionRequest);
            log.info("Unfollow User completed: {} followed {}", loggedInUser.getUserId(), userToFollow.getUserId());
        } catch (TransactionCanceledException e) {
            if (e.cancellationReasons() != null && !e.cancellationReasons().isEmpty() &&
                    "ConditionalCheckFailed".equals(e.cancellationReasons().getFirst().code())) {
                IdempotencyKey existing = idempotencyKeyRepository.table().getItem(
                        GetItemEnhancedRequest.builder()
                                .key(key -> key.partitionValue(idempotencyKey.getIdemKey()))
                                .consistentRead(true)
                                .build());
                if (existing != null) {
                    if (!requestHash.equals(existing.getRequestHash())) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "Idempotency-Key was already used with different content");
                    }
                    return Follower.builder()
                            .userId(loggedInUser.getUserId())
                            .followerId(userToFollow.getUserId())
                            .build();
                }
            }
            log.error("Unfollow User failed", e);
            throw e;
        }
        return follower;
    }

    private record RequestContent(String userId, String followerId) {}

    private String requestHash(Follower follower) {
        String json = MapperUtil.toJson(new RequestContent(follower.getUserId(), follower.getFollowerId()))
                .getOrElseThrow(e -> new RuntimeException("Cannot serialize create-post request", e));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private IdempotencyKey createIdempotency(Follower follower, String requestHash, String idemKey) {
        return IdempotencyKey.builder()
                .idemKey(String.format("%s#%s#%s#%s", IdempotencyKey.IdemType.UNFOLLOW, follower.getUserId(), follower.getFollowerId(), idemKey)) // FOLLOW#{userId}#{key}
                .requestHash(requestHash)
                .createdAt(LocalDateTime.now())
                .expiresAt(Instant.now().plus(Duration.ofHours(24)).getEpochSecond())
                .build();
    }
}
