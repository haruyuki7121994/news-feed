package com.learning.newsfeed.configurations.dynamodb;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Repository
@RequiredArgsConstructor
public abstract class SimpleDynamoDbCrudRepository<T extends DynamoDbBaseEntity> implements DynamoDbCrudRepository<T> {

    @Autowired
    protected DynamoDbEnhancedClient dynamoDbEnhancedClient;

    protected abstract Class<T> getEntityClass();

    @Override
    public DynamoDbTable<T> table() {
        String tableName = getEntityClass().getAnnotation(DynamoTable.class).name();
        return dynamoDbEnhancedClient.table(tableName, TableSchema.fromBean(getEntityClass()));
    }

    @Override
    public Optional<T> query(String pk) {
        return query(pk, null);
    }

    @Override
    public Optional<T> query(String pk, String sk) {
        Key key = buildKey(pk, sk);
        return Optional.ofNullable(table().getItem(key));
    }

    @Override
    public List<T> queryAll(String pk) {
        Key key = buildKey(pk, null);
        QueryEnhancedRequest request = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.keyEqualTo(key))
                .build();
        return table().query(request).items().stream().toList();
    }

    @Override
    public DynamoPaginatedResult<T> queryCursor(String pk, @Valid DynamoPaginator paginator) {
        Key key = buildKey(pk, null);

        var request = buildQueryRequest(key, paginator.getSize(), paginator.getCursor());

        var resultPage = table().query(request).iterator().next();
        var items = resultPage.items().stream().toList();
        var lastEvaluatedKey = resultPage.lastEvaluatedKey();

        return DynamoPaginatedResult.<T>builder()
                .items(items)
                .paginator(
                        DynamoPaginator.builder()
                                .size(paginator.getSize())
                                .cursor(lastEvaluatedKey)
                                .build()
                )
                .build();
    }

    @Override
    public void save(T entity) {
        table().putItem(entity);
    }

    @Override
    public T update(T entity) {
        return table().updateItem(entity);
    }

    @Override
    public void delete(T entity) {
        table().deleteItem(entity);
    }

    @Override
    public List<T> scanAll() {
        return table().scan().items().stream().collect(Collectors.toList());
    }

    protected Key buildKey(Object pk, Object sk) {
        Key.Builder keyBuilder = Key.builder();
        if (pk instanceof Number) {
            keyBuilder.partitionValue(((Number) pk).longValue());
        } else {
            keyBuilder.partitionValue(pk.toString());
        }
        if (sk != null) {
            if (sk instanceof Number) {
                keyBuilder.sortValue(((Number) sk).longValue());
            } else {
                keyBuilder.sortValue(sk.toString());
            }
        }
        return keyBuilder.build();
    }

    private QueryEnhancedRequest buildQueryRequest(
            Key key,
            int size,
            Map<String, AttributeValue> nextCursor) {

        if (size <= 0) {
            throw new IllegalArgumentException("size must be greater than 0");
        }

        var builder = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.keyEqualTo(key))
                .limit(size);

        if (nextCursor != null && !nextCursor.isEmpty()) {
            builder.exclusiveStartKey(nextCursor);
        }

        return builder.build();
    }
}
