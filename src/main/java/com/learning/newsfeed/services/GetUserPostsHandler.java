package com.learning.newsfeed.services;

import com.learning.newsfeed.configurations.dynamodb.DynamoPaginatedResult;
import com.learning.newsfeed.configurations.dynamodb.DynamoPaginator;
import com.learning.newsfeed.entities.Post;
import com.learning.newsfeed.entities.User;
import com.learning.newsfeed.repositories.PostRepository;
import com.learning.newsfeed.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GetUserPostsHandler {

    private final PostRepository postRepository;
    private final UserRepository userRepository;

    public DynamoPaginatedResult<Post> handle(String userId, DynamoPaginator paginator) {
        User user = userRepository.query(userId).orElseThrow();
        return postRepository.queryCursor(user.getUserId(), paginator);
    }
}
