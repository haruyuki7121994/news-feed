package com.learning.newsfeed.entities;

import com.learning.newsfeed.configurations.dynamodb.DynamoDbBaseEntity;
import com.learning.newsfeed.configurations.dynamodb.DynamoTable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
@DynamoTable(name = "outboxes")
public class Outbox implements DynamoDbBaseEntity {

    public static final String EVENT_PREFIX = "EVENT#";

    @Getter(onMethod_ = {
            @DynamoDbPartitionKey,
            @DynamoDbAttribute("PK")
    })
    private String eventId; // Giá trị lưu: EVENT#{eventId}
    private EventType eventType;
    private String aggregateId;
    private String payload;
    private EventStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime publishedAt;
    private int retryCount;
    private Long expiresAt;

    public enum EventType {
        POST_CREATED,
        POST_UPDATED,
        POST_DELETED,
    }

    public enum EventStatus {
        PENDING,
        PUBLISHED,
        FAILED,
    }
}
