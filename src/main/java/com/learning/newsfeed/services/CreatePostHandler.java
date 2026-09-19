package com.learning.newsfeed.services;

import com.learning.newsfeed.dto.req.CreatePostDTO;
import com.learning.newsfeed.entities.Outbox;
import com.learning.newsfeed.entities.Post;
import com.learning.newsfeed.repositories.OutboxRepository;
import com.learning.newsfeed.repositories.PostRepository;
import com.learning.newsfeed.repositories.UserFeedRepository;
import com.learning.newsfeed.utils.MapperUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactWriteItemsEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreatePostHandler implements Consumer<CreatePostDTO> {

    private final PostRepository postRepository;
    private final OutboxRepository outboxRepository;
    private final DynamoDbEnhancedClient enhancedClient;
    private final UserFeedRepository userFeedRepository;

    @Override
    public void accept(CreatePostDTO createPostDTO) {
        if (createPostDTO.getUserId() == null) throw new IllegalArgumentException("User id is required");
        Post newPost = CreatePostDTO.toNewEntity(createPostDTO, createPostDTO.getUserId());
        Outbox outbox = createOutbox(newPost);
        try {
            // Build the atomic transaction package
            TransactWriteItemsEnhancedRequest transactionRequest = TransactWriteItemsEnhancedRequest.builder()
                    .addPutItem(postRepository.table(), newPost)
                    .addPutItem(outboxRepository.table(), outbox)
                    .build();

            // Execute the batch call. If any operation fails, the entire payload is rejected.
            enhancedClient.transactWriteItems(transactionRequest);
            userFeedRepository.addToLatestFeed(newPost.getAuthorId(), newPost.getPostId(), newPost.getCreatedAt());
            log.info("Created Post completed: {} - {}", newPost.getPostId(), outbox.getEventId());

        } catch (TransactionCanceledException e) {
            userFeedRepository.removePost(newPost.getAuthorId(), newPost.getPostId());
            throw new RuntimeException("Created Post processing failed.", e);
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
}
