package com.learning.newsfeed.repositories;

import com.learning.newsfeed.configurations.dynamodb.SimpleDynamoDbCrudRepository;
import com.learning.newsfeed.entities.Follower;
import org.springframework.stereotype.Repository;

@Repository
public class FollowerRepository extends SimpleDynamoDbCrudRepository<Follower> {

    @Override
    protected Class<Follower> getEntityClass() {
        return Follower.class;
    }
}
