package com.learning.newsfeed.repositories;

import com.learning.newsfeed.configurations.dynamodb.SimpleDynamoDbCrudRepository;
import com.learning.newsfeed.entities.IdempotencyKey;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyKeyRepository extends SimpleDynamoDbCrudRepository<IdempotencyKey> {

    @Override
    protected Class<IdempotencyKey> getEntityClass() {
        return IdempotencyKey.class;
    }
}
