package com.learning.newsfeed.command;

import com.learning.newsfeed.entities.Follower;
import com.learning.newsfeed.repositories.UserRepository;
import com.learning.newsfeed.services.FollowUserHandler;
import com.learning.newsfeed.services.RegisterHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.datafaker.Faker;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserSeeder implements CommandLineRunner {

    private final RegisterHandler registerHandler;
    private final FollowUserHandler followUserHandler;
    private final UserRepository userRepository;

    @Override
    public void run(String... args) {
//        seedUserData();
//        addFollowersToUser(40, "blair.schmidt", getUsers());
    }

    private void seedUserData() {
        List<String> userIds = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Faker faker = new Faker();

            String username = faker.credentials().username();
            String displayName = faker.name().fullName();
            userIds.add(registerHandler.handle(username, displayName, "123456"));
        }

        for (int i = 0; i < userIds.size(); i++) {
            int randomNum = ThreadLocalRandom.current().nextInt(1, userIds.size() - 1);
            followUsers(randomNum, userIds.get(i), userIds);
        }
    }

    protected List<String> getUsers() {
        return userRepository.scanAll().stream()
                .filter(user -> user.getUserId().startsWith("USER#"))
                .map(user -> user.getUserId().replace("USER#", ""))
                .collect(Collectors.toList());
    }

    protected void followUsers(int length, String userId, List<String> userIds) {
        log.info("UserId={} can follow size={}", userId, length);
        for (int i = 0; i < length; i++) {
            int randomNum = ThreadLocalRandom.current().nextInt(0, userIds.size() - 1);
            String followingId = userIds.get(randomNum);
            if (followingId.equals(userId)) continue;
            Follower follower = followUserHandler.apply(userId, followingId, UUID.randomUUID().toString());
            log.info("UserId={} following userId={}", follower.getUserId(), follower.getFollowerId());
        }
    }

    protected void addFollowersToUser(int length, String userId, List<String> userIds) {
        log.info("UserId={} add size={} followers", userId, length);
        for (int i = 0; i < length; i++) {
            int randomNum = ThreadLocalRandom.current().nextInt(0, userIds.size() - 1);
            String followingId = userIds.get(randomNum);
            if (followingId.equals(userId)) continue;
            Follower follower = followUserHandler.apply(followingId, userId, UUID.randomUUID().toString());
            log.info("UserId={} add userId={}", follower.getUserId(), follower.getFollowerId());
        }
    }
}
