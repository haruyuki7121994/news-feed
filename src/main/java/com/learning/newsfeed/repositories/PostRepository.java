package com.learning.newsfeed.repositories;

import com.learning.newsfeed.configurations.dynamodb.SimpleDynamoDbCrudRepository;
import com.learning.newsfeed.entities.Post;
import org.springframework.stereotype.Repository;

@Repository
public class PostRepository extends SimpleDynamoDbCrudRepository<Post> {

    @Override
    protected Class<Post> getEntityClass() {
        return Post.class;
    }
}
