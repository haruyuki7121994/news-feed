package com.learning.newsfeed.repositories;

import com.learning.newsfeed.configurations.dynamodb.SimpleDynamoDbCrudRepository;
import com.learning.newsfeed.entities.Authentication;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class AuthenticationRepository extends SimpleDynamoDbCrudRepository<Authentication> {

    @Override
    protected Class<Authentication> getEntityClass() {
        return Authentication.class;
    }

    public Optional<org.springframework.security.core.Authentication> getAuthContext() {
        return Optional.ofNullable(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication());
    }
}
