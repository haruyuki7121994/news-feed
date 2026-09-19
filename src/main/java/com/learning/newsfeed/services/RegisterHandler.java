package com.learning.newsfeed.services;

import com.learning.newsfeed.entities.Authentication;
import com.learning.newsfeed.entities.ScopeGroups;
import com.learning.newsfeed.entities.User;
import com.learning.newsfeed.repositories.AuthenticationRepository;
import com.learning.newsfeed.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactWriteItemsEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RegisterHandler {

    private final UserRepository userRepository;
    private final AuthenticationRepository authenticationRepository;
    private final PasswordEncoder passwordEncoder;
    private final DynamoDbEnhancedClient enhancedClient;

    public void handle(String username, String displayName, String password) {
        User user = User.builder()
                .userId(User.USER_PREFIX + UUID.randomUUID())
                .username(username)
                .displayName(displayName)
                .passwordHash(passwordEncoder.encode(password))
                .createdAt(LocalDateTime.now())
                .build();

        Authentication authentication = Authentication.builder()
                .username(Authentication.USER_PREFIX + username)
                .userId(user.getUserId())
                .scopes(new HashSet<>(ScopeGroups.MEMBER))
                .build();

        try {
            // Build the atomic transaction package
            TransactWriteItemsEnhancedRequest transactionRequest = TransactWriteItemsEnhancedRequest.builder()
                    .addPutItem(userRepository.table(), user)
                    .addPutItem(authenticationRepository.table(), authentication)
                    .build();

            // Execute the batch call. If any operation fails, the entire payload is rejected.
            enhancedClient.transactWriteItems(transactionRequest);
            log.info("Registering user completed: {} - {}", username, displayName);

        } catch (TransactionCanceledException e) {
            throw new RuntimeException("Register processing failed.", e);
        }
    }
}
