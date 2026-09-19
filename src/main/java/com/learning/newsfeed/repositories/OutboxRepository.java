package com.learning.newsfeed.repositories;

import com.learning.newsfeed.configurations.dynamodb.SimpleDynamoDbCrudRepository;
import com.learning.newsfeed.entities.Outbox;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository extends SimpleDynamoDbCrudRepository<Outbox> {

    @Override
    protected Class<Outbox> getEntityClass() {
        return Outbox.class;
    }
}
