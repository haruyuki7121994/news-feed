package com.learning.newsfeed.configurations.dynamodb;

import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DynamoPaginator {

    @Positive(message = "Size must be greater than zero")
    private int size;
    private Map<String, AttributeValue> cursor;
}
