package com.learning.newsfeed.repositories;

import com.learning.newsfeed.configurations.dynamodb.SimpleDynamoDbCrudRepository;
import com.learning.newsfeed.entities.User;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository extends SimpleDynamoDbCrudRepository<User> {

    @Override
    protected Class<User> getEntityClass() {
        return User.class;
    }
}
