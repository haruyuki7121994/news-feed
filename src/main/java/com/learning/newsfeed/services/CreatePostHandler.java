package com.learning.newsfeed.services;

import com.learning.newsfeed.dto.req.CreatePostDTO;
import com.learning.newsfeed.entities.IdempotencyKey;
import com.learning.newsfeed.entities.Outbox;
import com.learning.newsfeed.entities.Post;
import com.learning.newsfeed.repositories.IdempotencyKeyRepository;
import com.learning.newsfeed.repositories.OutboxRepository;
import com.learning.newsfeed.repositories.PostRepository;
import com.learning.newsfeed.repositories.UserFeedRepository;
import com.learning.newsfeed.utils.MapperUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.model.GetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactPutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactWriteItemsEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreatePostHandler {

    public record CreatePostResult(String postId) {}

    private record RequestContent(String text, List<String> medias) {}

    private final PostRepository postRepository;
    private final OutboxRepository outboxRepository;
    private final DynamoDbEnhancedClient enhancedClient;
    private final UserFeedRepository userFeedRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;

    public CreatePostResult create(CreatePostDTO createPostDTO) {
        if (createPostDTO.getUserId() == null) throw new IllegalArgumentException("User id is required");
        if (createPostDTO.getIdempotencyKey() == null ||
                !createPostDTO.getIdempotencyKey().matches("[A-Za-z0-9_-]{1,128}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Idempotency-Key");
        }
        String requestHash = requestHash(createPostDTO);
        Post newPost = CreatePostDTO.toNewEntity(createPostDTO, createPostDTO.getUserId());
        Outbox outbox = createOutbox(newPost);
        IdempotencyKey idempotencyKey = createIdempotency(createPostDTO, newPost, outbox, requestHash);
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
                    .addPutItem(idempotencyKeyRepository.table(), putRequestWithCondition)
                    .addPutItem(postRepository.table(), newPost)
                    .addPutItem(outboxRepository.table(), outbox)
                    .build();

            // Execute the batch call. If any operation fails, the entire payload is rejected.
            enhancedClient.transactWriteItems(transactionRequest);
            addToLatestFeed(newPost);
            log.info("Created Post completed: {} - {}", newPost.getPostId(), outbox.getEventId());
            return new CreatePostResult(newPost.getPostId());

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
                    return new CreatePostResult(existing.getPostId());
                }
            }
            throw e;
        }
    }

    private void addToLatestFeed(Post post) {
        try {
            userFeedRepository.addToLatestFeed(post.getAuthorId(), post.getPostId(), post.getCreatedAt());
        } catch (RuntimeException e) {
            // The DynamoDB transaction already committed; keep its successful API result.
            log.warn("Post {} was saved, but updating the author's Redis feed failed", post.getPostId(), e);
        }
    }

    private String requestHash(CreatePostDTO dto) {
        String json = MapperUtil.toJson(new RequestContent(dto.getText(), dto.getMedias()))
                .getOrElseThrow(e -> new RuntimeException("Cannot serialize create-post request", e));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private Outbox createOutbox(Post post) {
        return Outbox.builder()
                .eventId(Outbox.EVENT_PREFIX + UUID.randomUUID())
                .eventType(Outbox.EventType.POST_CREATED)
                .aggregateId(post.getPostId())
                .payload(MapperUtil.toJson(post).getOrElseThrow(e -> new RuntimeException(e.getMessage())))
                .status(Outbox.EventStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private IdempotencyKey createIdempotency(CreatePostDTO createPostDTO, Post post, Outbox outbox,
                                              String requestHash) {
        return IdempotencyKey.builder()
                .idemKey(String.format("%s#%s#%s", IdempotencyKey.IdemType.CREATE_POST, createPostDTO.getUserId(), createPostDTO.getIdempotencyKey())) // CREATE_POST#{userId}#{key}
                .postId(post.getPostId())
                .eventId(outbox.getEventId())
                .requestHash(requestHash)
                .createdAt(LocalDateTime.now())
                .expiresAt(Instant.now().plus(Duration.ofHours(24)).getEpochSecond())
                .build();
    }
}
