package com.learning.newsfeed.configurations.dynamodb;

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

import java.util.List;
import java.util.Optional;

public interface DynamoDbCrudRepository<T extends DynamoDbBaseEntity> {

    DynamoDbTable<T> table();

    Optional<T> query(String pk);
    Optional<T> query(String pk, String sk);
    List<T> queryAll(String pk);
    List<T> scanAll();
    DynamoPaginatedResult<T> queryCursor(String pk, DynamoPaginator paginator);
    void save(T entity);
    T update(T entity);
    void delete(T entity);
}
