package com.learning.newsfeed.command;

import com.learning.newsfeed.services.RegisterHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.datafaker.Faker;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserSeeder implements CommandLineRunner {

    private final RegisterHandler registerHandler;

    @Override
    public void run(String... args) {
//        seedUserData();
    }

    private void seedUserData() {
        for (int i = 0; i < 10; i++) {
            Faker faker = new Faker();

            String username = faker.credentials().username();
            String displayName = faker.name().fullName();
            registerHandler.handle(username, displayName, "123456");
        }
    }
}
