package com.learning.newsfeed.services;

import com.learning.newsfeed.entities.Follower;
import com.learning.newsfeed.entities.User;
import com.learning.newsfeed.repositories.FollowerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class UnfollowUserHandler implements Function<String, Follower> {

    private final FollowerRepository followerRepository;

    @Override
    public Follower apply(String userId) {
        User loggedInUser = null;
        Follower follower = followerRepository.query(loggedInUser.getUserId(), userId).orElseThrow();
        followerRepository.delete(follower);
        return follower;
    }
}
