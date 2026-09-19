package com.learning.newsfeed.configurations.dynamodb;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DynamoPaginatedResult<T> {

    private List<T> items;
    private DynamoPaginator paginator;
}
