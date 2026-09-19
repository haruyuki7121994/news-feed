package com.learning.newsfeed.services;

import com.learning.newsfeed.entities.Follower;
import com.learning.newsfeed.entities.IdempotencyKey;
import com.learning.newsfeed.entities.User;
import com.learning.newsfeed.repositories.AuthenticationRepository;
import com.learning.newsfeed.repositories.FollowerRepository;
import com.learning.newsfeed.repositories.IdempotencyKeyRepository;
import com.learning.newsfeed.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Runs the real Enhanced Client (including its version extension) against a scripted transport.
 * DynamoDB condition evaluation is not emulated: tests inject cancellation responses and inspect
 * the actual expressions sent by the SDK, as well as the subsequent retry/replay behavior. */
class FollowRelationshipServiceTest {
    private final Map<String, Map<String, AttributeValue>> items = new HashMap<>();
    private final List<TransactWriteItemsRequest> writes = new ArrayList<>();
    private Consumer<TransactWriteItemsRequest> beforeCommit = request -> {};
    private FollowRelationshipService service;
    private AuthenticationRepository auth;

    @BeforeEach
    void setUp() {
        DynamoDbClient transport = mock(DynamoDbClient.class);
        var enhanced = DynamoDbEnhancedClient.builder().dynamoDbClient(transport).build();
        var users = mock(UserRepository.class);
        var followers = mock(FollowerRepository.class);
        var receipts = mock(IdempotencyKeyRepository.class);
        auth = mock(AuthenticationRepository.class);
        when(users.table()).thenReturn(enhanced.table("users", TableSchema.fromBean(User.class)));
        when(followers.table()).thenReturn(enhanced.table("followers", TableSchema.fromBean(Follower.class)));
        when(receipts.table()).thenReturn(enhanced.table("idempotencyKeys", TableSchema.fromBean(IdempotencyKey.class)));
        when(auth.getAuthContext()).thenReturn(Optional.of(
                UsernamePasswordAuthenticationToken.authenticated("USER#A", "unused", List.of())));
        when(transport.getItem(any(GetItemRequest.class))).thenAnswer(invocation -> {
            GetItemRequest request = invocation.getArgument(0);
            assertEquals(Boolean.TRUE, request.consistentRead(), "All decision/replay reads must be consistent");
            return GetItemResponse.builder().item(items.get(key(request.tableName(), request.key()))).build();
        });
        when(transport.transactWriteItems(any(TransactWriteItemsRequest.class))).thenAnswer(invocation -> {
            TransactWriteItemsRequest request = invocation.getArgument(0);
            writes.add(request);
            beforeCommit.accept(request);
            commit(request);
            return TransactWriteItemsResponse.builder().build();
        });
        user("USER#A", 0, 1L);
        user("USER#B", 0, 3L);
        service = new FollowRelationshipService(users, followers, auth, receipts, enhanced);
    }

    @Test
    void followWritesCanonicalKeysAndUsesSdkVersionCondition() {
        Follower result = new FollowUserHandler(service).apply("B", "follow-1");
        assertEquals("USER#B", result.getUserId());
        assertEquals("FOLLOWER#USER#A", result.getFollowerId());
        assertEquals("FOLLOWER#USER#A", result.getFollowingPk());
        assertEquals("USER#B", result.getFollowingSk());
        assertEquals(1, count());
        assertEquals(4, version());
        var actions = writes.getFirst().transactItems();
        assertEquals("attribute_not_exists(PK)", actions.get(0).put().conditionExpression());
        assertEquals("attribute_not_exists(PK)", actions.get(1).put().conditionExpression());
        assertNotNull(actions.get(1).put().item().get("createdAt"));
        var userWrite = actions.get(2).put();
        assertTrue(userWrite.conditionExpression().contains("attribute_exists(PK)"));
        assertTrue(userWrite.expressionAttributeNames().containsValue("version"));
        assertTrue(userWrite.expressionAttributeValues().containsValue(n(3)));
        assertEquals(n(4), userWrite.item().get("version"));
    }

    @Test
    void sameKeyReplaysSameResponseWithoutAnotherWrite() {
        Follower first = service.setFollowing("B", "k1", true);
        assertEquals(first, service.setFollowing("USER#B", "k1", true));
        assertEquals(1, writes.size());
        assertEquals(1, count());
    }

    @Test
    void newKeyForExistingFollowDoesNotIncrementAndNoOpReceiptSurvivesUnfollow() {
        service.setFollowing("B", "k1", true);
        service.setFollowing("B", "k2", true);
        assertEquals(1, count());
        assertEquals(4, version());
        assertEquals("attribute_exists(PK)", writes.get(1).transactItems().get(1)
                .conditionCheck().conditionExpression());
        service.setFollowing("B", "u1", false);
        service.setFollowing("B", "k2", true);
        assertEquals(3, writes.size(), "Replaying old no-op follow must not follow again");
        assertEquals(0, count());
        assertNull(edge());
    }

    @Test
    void unfollowDeletesMatchingKeyOnceAndReplaysAfterDeletion() {
        service.setFollowing("B", "k1", true);
        Follower first = new UnfollowUserHandler(service).apply("B", "u1");
        assertEquals(first, service.setFollowing("B", "u1", false));
        assertEquals(2, writes.size());
        assertEquals(0, count());
        assertEquals(5, version());
        assertNull(edge());
        var delete = writes.get(1).transactItems().get(1).delete();
        assertEquals(s("USER#B"), delete.key().get("PK"));
        assertEquals(s("FOLLOWER#USER#A"), delete.key().get("SK"));
        assertEquals("attribute_exists(PK)", delete.conditionExpression());
    }

    @Test
    void unfollowAbsentEdgePersistsNoOpAndDoesNotUndoLaterFollow() {
        service.setFollowing("B", "u1", false);
        assertEquals(0, count());
        assertEquals(3, version());
        assertEquals("attribute_not_exists(PK)", writes.getFirst().transactItems().get(1)
                .conditionCheck().conditionExpression());
        service.setFollowing("B", "k1", true);
        service.setFollowing("B", "u1", false);
        assertEquals(2, writes.size());
        assertEquals(1, count());
        assertNotNull(edge());
    }

    @Test
    void receiptAndVersionCanFailTogetherAndReplayStillSucceeds() {
        beforeCommit = request -> {
            commit(request); // Another invocation committed the same request first.
            throw cancelled("ConditionalCheckFailed", "None", "ConditionalCheckFailed");
        };
        assertDoesNotThrow(() -> service.setFollowing("B", "k1", true));
        assertEquals(1, writes.size());
        assertEquals(1, count());
    }

    @Test
    void versionConflictRereadsCountAndVersionBeforeRetrying() {
        beforeCommit = request -> {
            if (writes.size() == 1) {
                user("USER#B", 1, 4L); // A different follower committed.
                throw cancelled("None", "None", "ConditionalCheckFailed");
            }
        };
        service.setFollowing("B", "k1", true);
        assertEquals(2, writes.size());
        assertEquals(2, count());
        assertEquals(5, version());
        assertTrue(writes.get(1).transactItems().get(2).put()
                .expressionAttributeValues().containsValue(n(4)));
    }

    @Test
    void concurrentFollowOfSamePairBecomesNoOpAfterRetry() {
        beforeCommit = request -> {
            if (writes.size() == 1) {
                edge(true);
                user("USER#B", 1, 4L);
                throw cancelled("None", "ConditionalCheckFailed", "ConditionalCheckFailed");
            }
        };
        service.setFollowing("B", "k1", true);
        assertEquals(1, count());
        assertEquals(4, version());
        assertNotNull(writes.get(1).transactItems().get(1).conditionCheck());
    }

    @Test
    void concurrentDeleteBecomesNoOpWithoutDecrementingTwice() {
        edge(true);
        user("USER#B", 1, 3L);
        beforeCommit = request -> {
            if (writes.size() == 1) {
                edge(false);
                user("USER#B", 0, 4L);
                throw cancelled("None", "ConditionalCheckFailed", "ConditionalCheckFailed");
            }
        };
        service.setFollowing("B", "u1", false);
        assertEquals(0, count());
        assertEquals(4, version());
        assertNotNull(writes.get(1).transactItems().get(1).conditionCheck());
    }

    @Test
    void oppositeRequestRacingNoOpIsReevaluated() {
        edge(true);
        user("USER#B", 1, 3L);
        beforeCommit = request -> {
            if (writes.size() == 1) {
                edge(false);
                user("USER#B", 0, 4L);
                throw cancelled("None", "ConditionalCheckFailed", "None");
            }
        };
        service.setFollowing("B", "k1", true);
        assertNotNull(edge());
        assertEquals(1, count());
        assertEquals(5, version());
    }

    @Test
    void exhaustedVersionConflictsReturn409WithoutCommittingOwnChanges() {
        beforeCommit = request -> { throw cancelled("None", "None", "ConditionalCheckFailed"); };
        assertStatus(HttpStatus.CONFLICT, () -> service.setFollowing("B", "k1", true));
        assertEquals(3, writes.size());
        assertEquals(0, count());
        assertNull(edge());
    }

    @Test
    void transactionConflictIsRetriedButValidationFailureIsNot() {
        beforeCommit = request -> {
            if (writes.size() == 1) {
                throw cancelled("None", "TransactionConflict", "None");
            }
        };
        service.setFollowing("B", "k1", true);
        assertEquals(2, writes.size());
        var failure = cancelled("None", "None", "ValidationError");
        beforeCommit = request -> { throw failure; };
        assertSame(failure, assertThrows(TransactionCanceledException.class,
                () -> service.setFollowing("B", "u1", false)));
        assertEquals(3, writes.size());
        assertEquals(1, count());
    }

    @Test
    void sameKeyForDifferentTargetReturns409() {
        service.setFollowing("B", "k1", true);
        assertStatus(HttpStatus.CONFLICT, () -> service.setFollowing("C", "k1", true));
        assertEquals(1, writes.size());
    }

    @Test
    void receiptReplayDoesNotDependOnTargetStillExisting() {
        service.setFollowing("B", "k1", true);
        items.remove("users|USER#B|");
        assertDoesNotThrow(() -> service.setFollowing("B", "k1", true));
        assertEquals(1, writes.size());
    }

    @Test
    void legacyUserWithoutVersionGetsInitialVersionAndExistenceGuard() {
        user("USER#B", 0, null);
        service.setFollowing("B", "k1", true);
        assertEquals(1, version()); // SDK 2.50.3 initializes a missing version to startAt + incrementBy.
        var write = writes.getFirst().transactItems().get(2).put();
        assertTrue(write.conditionExpression().contains("attribute_exists(PK)"));
        assertTrue(write.conditionExpression().contains("attribute_not_exists"));
        assertTrue(write.expressionAttributeNames().containsValue("version"));
    }

    @Test
    void inconsistentCounterIsRejectedWithoutDeletingRelationship() {
        edge(true);
        assertStatus(HttpStatus.CONFLICT, () -> service.setFollowing("B", "u1", false));
        assertTrue(writes.isEmpty());
        assertNotNull(edge());
    }

    @Test
    void invalidInputsAndMissingUsersDoNotWrite() {
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.setFollowing("A", "k1", true));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.setFollowing("B", "", true));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.setFollowing("B", null, true));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.setFollowing("USER#", "k1", true));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.setFollowing("missing", "k1", true));
        when(auth.getAuthContext()).thenReturn(Optional.empty());
        assertStatus(HttpStatus.UNAUTHORIZED, () -> service.setFollowing("B", "k1", true));
        assertTrue(writes.isEmpty());
    }

    private void commit(TransactWriteItemsRequest request) {
        for (var action : request.transactItems()) {
            if (action.put() != null) {
                items.put(key(action.put().tableName(), action.put().item()), new HashMap<>(action.put().item()));
            } else if (action.delete() != null) {
                items.remove(key(action.delete().tableName(), action.delete().key()));
            }
        }
    }

    private void user(String id, int count, Long version) {
        items.put("users|" + id + "|", TableSchema.fromBean(User.class).itemToMap(User.builder()
                .userId(id).followersCount(count).version(version).build(), true));
    }

    private void edge(boolean exists) {
        String key = "followers|USER#B|FOLLOWER#USER#A";
        if (exists) {
            items.put(key, Map.of("PK", s("USER#B"), "SK", s("FOLLOWER#USER#A")));
        } else {
            items.remove(key);
        }
    }

    private Map<String, AttributeValue> edge() { return items.get("followers|USER#B|FOLLOWER#USER#A"); }
    private int count() { return Integer.parseInt(items.get("users|USER#B|").get("followersCount").n()); }
    private long version() { return Long.parseLong(items.get("users|USER#B|").get("version").n()); }
    private static AttributeValue s(String value) { return AttributeValue.builder().s(value).build(); }
    private static AttributeValue n(long value) { return AttributeValue.builder().n(Long.toString(value)).build(); }
    private static String key(String table, Map<String, AttributeValue> item) {
        return table + "|" + item.get("PK").s() + "|" + item.getOrDefault("SK", s("")).s();
    }
    private static TransactionCanceledException cancelled(String... codes) {
        return TransactionCanceledException.builder().cancellationReasons(Arrays.stream(codes)
                .map(code -> CancellationReason.builder().code(code).build()).toList()).build();
    }
    private static void assertStatus(HttpStatus expected, Runnable action) {
        assertEquals(expected, assertThrows(ResponseStatusException.class, action::run).getStatusCode());
    }
}
