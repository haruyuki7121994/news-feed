package com.learning.newsfeed.services;

import com.learning.newsfeed.entities.Follower;
import com.learning.newsfeed.entities.User;
import com.learning.newsfeed.repositories.FollowerRepository;
import com.learning.newsfeed.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class FollowUserHandler implements Function<String, Follower> {

    private final UserRepository userRepository;
    private final FollowerRepository followerRepository;


    @Override
    public Follower apply(String s) {
        User loggedInUser = null;
        User userToFollow = userRepository.query(s).orElseThrow();
        Follower newFollower = Follower.builder()
                .userId(User.USER_PREFIX + loggedInUser.getUserId())
                .followerId(Follower.FOLLOWER_PREFIX + userToFollow.getUserId())
                .followingPk(Follower.FOLLOWER_PREFIX + userToFollow.getUserId())
                .followingSk(Follower.FOLLOWER_PREFIX + userToFollow.getUserId())
                .build();
        followerRepository.save(newFollower);
        return newFollower;
    }
}
