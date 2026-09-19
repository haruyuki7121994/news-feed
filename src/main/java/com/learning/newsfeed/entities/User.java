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
@DynamoTable(name = "users")
public class User implements DynamoDbBaseEntity {

    public static final String USER_PREFIX = "USER#";

    @Getter(onMethod_ = {
            @DynamoDbPartitionKey,
            @DynamoDbAttribute("PK")
    })
    private String userId; // Giá trị lưu: USER#{userId}
    private String username;
    private String passwordHash;
    private String displayName;
    private LocalDateTime createdAt;
}
