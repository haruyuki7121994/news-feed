package com.learning.newsfeed.repositories;

import com.learning.newsfeed.configurations.dynamodb.SimpleDynamoDbCrudRepository;
import com.learning.newsfeed.entities.Authentication;
import org.springframework.stereotype.Repository;

@Repository
public class AuthenticationRepository extends SimpleDynamoDbCrudRepository<Authentication> {

    @Override
    protected Class<Authentication> getEntityClass() {
        return Authentication.class;
    }
}
