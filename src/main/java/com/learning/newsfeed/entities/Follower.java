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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
@DynamoTable(name = "followers")
public class Follower implements DynamoDbBaseEntity {

    public static final String FOLLOWER_PREFIX = "FOLLOWER#";

    @Getter(onMethod_ = {
            @DynamoDbPartitionKey,
            @DynamoDbAttribute("PK")
    })
    private String userId; // Author được follow: USER#B khi A follow B.

    @Getter(onMethod_ = {
            @DynamoDbSortKey,
            @DynamoDbAttribute("SK")
    })
    private String followerId; // Người đi follow: FOLLOWER#USER#A.
    private LocalDateTime createdAt;

    @Getter(onMethod_ = {
            @DynamoDbSecondaryPartitionKey(indexNames = "GSI1"),
            @DynamoDbAttribute("GSI1PK")
    })
    private String followingPk; // FOLLOWER#USER#A: query các author mà A đang follow.

    @Getter(onMethod_ = {
            @DynamoDbSecondarySortKey(indexNames = "GSI1"),
            @DynamoDbAttribute("GSI1SK")
    })
    private String followingSk; // USER#B.
}
