package com.learning.newsfeed.entities;

import com.learning.newsfeed.configurations.dynamodb.DynamoDbBaseEntity;
import com.learning.newsfeed.configurations.dynamodb.DynamoTable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
@DynamoTable(name = "posts")
public class Post implements DynamoDbBaseEntity {

    public static final String POST_PREFIX = "POST#";
    public static final String POST_BY_AUTHOR_PREFIX = "AUTHOR#";

    @Getter(onMethod_ = {
            @DynamoDbPartitionKey,
            @DynamoDbAttribute("PK")
    })
    private String postId; // Giá trị lưu: POST#{postId}
    private String authorId;
    private String text;
    private List<String> mediaIds;
    private LocalDateTime createdAt;
    private LocalDateTime deletedAt;

    @Getter(onMethod_ = {
            @DynamoDbSecondaryPartitionKey(indexNames = "GSI1"),
            @DynamoDbAttribute("GSI1PK")
    })
    private String postByAuthorPk;

    @Getter(onMethod_ = {
            @DynamoDbSecondarySortKey(indexNames = "GSI1"),
            @DynamoDbAttribute("GSI1SK")
    })
    private String postByAuthorSk;
}
