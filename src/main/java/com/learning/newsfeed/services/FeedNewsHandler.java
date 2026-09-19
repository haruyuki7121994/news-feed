package com.learning.newsfeed.services;

import com.learning.newsfeed.configurations.dynamodb.DynamoPaginatedResult;
import com.learning.newsfeed.configurations.dynamodb.DynamoPaginator;
import com.learning.newsfeed.entities.Post;
import com.learning.newsfeed.entities.User;
import com.learning.newsfeed.repositories.PostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.function.Function;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedNewsHandler implements Function<DynamoPaginator, DynamoPaginatedResult<Post>> {

    private final PostRepository postRepository;

    @Override
    public DynamoPaginatedResult<Post> apply(DynamoPaginator paginator) {
        User user = null;
        return postRepository.queryCursor(user.getUserId(), paginator);
    }
}
