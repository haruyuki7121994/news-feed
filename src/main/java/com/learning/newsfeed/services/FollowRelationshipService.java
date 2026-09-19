package com.learning.newsfeed.services;

import com.learning.newsfeed.entities.Follower;
import com.learning.newsfeed.entities.IdempotencyKey;
import com.learning.newsfeed.entities.Outbox;
import com.learning.newsfeed.entities.User;
import com.learning.newsfeed.repositories.AuthenticationRepository;
import com.learning.newsfeed.repositories.FollowerRepository;
import com.learning.newsfeed.repositories.IdempotencyKeyRepository;
import com.learning.newsfeed.repositories.OutboxRepository;
import com.learning.newsfeed.repositories.UserRepository;
import com.learning.newsfeed.utils.MapperUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.ConditionCheck;
import software.amazon.awssdk.enhanced.dynamodb.model.GetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactDeleteItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactPutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactWriteItemsEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class FollowRelationshipService {

    private static final int MAX_ATTEMPTS = 3;
    // Thứ tự transaction: idempotency [0], quan hệ [1], User/version [2], outbox Unfollow [3].
    private static final int RECEIPT_INDEX = 0;

    private final UserRepository userRepository;
    private final FollowerRepository followerRepository;
    private final AuthenticationRepository authenticationRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final OutboxRepository outboxRepository;
    private final DynamoDbEnhancedClient enhancedClient;

    public Follower setFollowing(String targetId, String idempotencyKey, boolean following) {
        // Bước 1: kiểm tra request và lấy người thao tác từ security context, không từ client.
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Idempotency-Key");
        }
        Authentication authentication = authenticationRepository.getAuthContext()
                .filter(Authentication::isAuthenticated)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        String actorId = userKey(authentication.getName());
        String targetKey = userKey(targetId);
        if (actorId.equals(targetKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot follow or unfollow yourself");
        }

        // Bước 2: A follow B => PK=USER#B, SK=FOLLOWER#USER#A.
        // GSI đảo chiều để tìm các author mà A đang follow; không thêm USER# hai lần.
        Follower relation = Follower.builder()
                .userId(targetKey)
                .followerId(Follower.FOLLOWER_PREFIX + actorId)
                .followingPk(Follower.FOLLOWER_PREFIX + actorId)
                .followingSk(targetKey)
                .build();
        var operation = following ? IdempotencyKey.IdemType.FOLLOW : IdempotencyKey.IdemType.UNFOLLOW;
        // Key có scope theo thao tác + actor. Target nằm trong hash để phát hiện dùng lại
        // cùng key cho target khác. Bản ghi này chỉ được lưu khi transaction thành công.
        IdempotencyKey receipt = IdempotencyKey.builder()
                .idemKey(operation + "#" + actorId + "#" + idempotencyKey)
                .requestHash(hash(operation + "\n" + actorId + "\n" + targetKey))
                .createdAt(LocalDateTime.now())
                .expiresAt(Instant.now().plus(Duration.ofHours(24)).getEpochSecond())
                .build();

        // Bước 3: kiểm tra idempotency trước khi đọc quan hệ. Unfollow retry vẫn thành công
        // khi quan hệ đã bị xóa; request cũ cũng không đảo ngược một thao tác mới hơn.
        if (replayed(receipt)) {
            return relation;
        }
        readUser(actorId);
        Key relationKey = Key.builder().partitionValue(targetKey)
                .sortValue(relation.getFollowerId()).build();
        // Chuẩn bị một eventId cố định cho request. Event chỉ được lưu nếu transaction
        // thực sự xóa quan hệ; retry version không tạo thêm eventId/outbox khác.
        Outbox unfollowEvent = following ? null : createUnfollowEvent(actorId, targetKey);

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (attempt > 1 && replayed(receipt)) {
                return relation;
            }
            // Bước 4: mỗi lượt retry đều đọc lại User và quan hệ với consistentRead.
            // Không dùng lại count/version đã đọc ở lượt trước.
            User target = readUser(targetKey);
            Follower existing = followerRepository.table().getItem(GetItemEnhancedRequest.builder()
                    .key(relationKey).consistentRead(true).build());
            boolean changesRelation = following != (existing != null);
            boolean emitsUnfollow = !following && changesRelation;
            // Lưu liên kết đến outbox trong receipt. Nếu retry trở thành no-op thì bỏ liên kết,
            // vì event ứng viên chưa từng được commit bởi request này.
            receipt.setEventId(emitsUnfollow ? unfollowEvent.getEventId() : null);

            // Bước 5: thêm idempotency ở index 0 để chỉ một request cùng key được commit.
            var transaction = TransactWriteItemsEnhancedRequest.builder()
                    .addPutItem(idempotencyKeyRepository.table(),
                            TransactPutItemEnhancedRequest.builder(IdempotencyKey.class)
                                    .item(receipt).conditionExpression(condition("attribute_not_exists(PK)"))
                                    .build());

            if (changesRelation) {
                // Bước 6a: chỉ thay đổi count khi thực sự tạo/xóa quan hệ. Điều kiện trên
                // Put/Delete bảo vệ trước request đồng thời thay đổi quan hệ sau bước đọc.
                if (target.getFollowersCount() < 0 || (!following && target.getFollowersCount() == 0)
                        || (following && target.getFollowersCount() == Integer.MAX_VALUE)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "followersCount is inconsistent; reconcile the follower data");
                }
                if (following) {
                    Follower newRelation = Follower.builder()
                            .userId(relation.getUserId()).followerId(relation.getFollowerId())
                            .followingPk(relation.getFollowingPk()).followingSk(relation.getFollowingSk())
                            .createdAt(LocalDateTime.now()).build();
                    transaction.addPutItem(followerRepository.table(),
                            TransactPutItemEnhancedRequest.builder(Follower.class).item(newRelation)
                                    .conditionExpression(condition("attribute_not_exists(PK)")).build());
                } else {
                    transaction.addDeleteItem(followerRepository.table(),
                            TransactDeleteItemEnhancedRequest.builder().key(relationKey)
                                    .conditionExpression(condition("attribute_exists(PK)")).build());
                }
                target.setFollowersCount(target.getFollowersCount() + (following ? 1 : -1));
                transaction.addPutItem(userRepository.table(),
                        TransactPutItemEnhancedRequest.builder(User.class).item(target)
                                // Giữ optimistic locking: SDK thêm điều kiện version cũ và tăng
                                // version mới nhờ @DynamoDbVersionAttribute trên User.
                                // Điều kiện tồn tại tránh tạo lại User vừa bị request khác xóa.
                                .conditionExpression(condition("attribute_exists(PK)")).build());
            } else {
                // Bước 6b: trạng thái đã đúng thì không sửa User/count/version, nhưng vẫn lưu
                // idempotency. ConditionCheck xác nhận trạng thái còn đúng tại lúc commit.
                // Nhờ lưu cả no-op, retry request cũ sau thao tác ngược chiều vẫn an toàn.
                transaction.addConditionCheck(followerRepository.table(), ConditionCheck.builder()
                        .key(relationKey).conditionExpression(condition(
                                following ? "attribute_exists(PK)" : "attribute_not_exists(PK)"))
                        .build());
                transaction.addConditionCheck(userRepository.table(), ConditionCheck.builder()
                        .key(Key.builder().partitionValue(targetKey).build())
                        .conditionExpression(condition("attribute_exists(PK)")).build());
            }

            // Bước 6c: chỉ khi thực sự Unfollow, ghi UNFOLLOWED vào cùng transaction.
            // Payload xác định feed của actor và author cần loại bài; worker sau này dùng ZREM
            // các postId của author ở latest/ranked, không DEL toàn bộ feed của actor.
            if (emitsUnfollow) {
                transaction.addPutItem(outboxRepository.table(),
                        TransactPutItemEnhancedRequest.builder(Outbox.class).item(unfollowEvent)
                                .conditionExpression(condition("attribute_not_exists(PK)")).build());
            }

            try {
                // Bước 7: commit nguyên tử idempotency + quan hệ + count/version + outbox (nếu có).
                enhancedClient.transactWriteItems(transaction.build());
                // Response chỉ chứa định danh quan hệ để lần đầu, replay và no-op giống nhau.
                return relation;
            } catch (TransactionCanceledException exception) {
                // Bước 8: kiểm tra idempotency trước, kể cả khi index 2 cũng lỗi version.
                // Receipt hợp lệ chứng minh request này đã được một invocation khác commit.
                var reasons = exception.cancellationReasons();
                if (reasons.size() > RECEIPT_INDEX
                        && "ConditionalCheckFailed".equals(reasons.get(RECEIPT_INDEX).code())
                        && replayed(receipt)) {
                    return relation;
                }
                if (!retryable(exception)) {
                    // Không biến lỗi validation/capacity hoặc lỗi không rõ nguyên nhân thành thành công.
                    throw exception;
                }
                if (attempt == MAX_ATTEMPTS) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Concurrent follow/unfollow update; retry with the same Idempotency-Key", exception);
                }
                // Chỉ retry lỗi điều kiện/race đã biết; lượt sau đọc lại toàn bộ trạng thái.
                backoff(attempt);
            }
        }
        throw new IllegalStateException("Unreachable retry state");
    }

    private record UnfollowedPayload(String followerId, String authorId, String unfollowedAt) {}

    private static Outbox createUnfollowEvent(String actorId, String targetKey) {
        Instant now = Instant.now();
        String payload = MapperUtil.toJson(new UnfollowedPayload(actorId, targetKey, now.toString()))
                .getOrElseThrow(error -> new IllegalStateException("Cannot serialize UNFOLLOWED event", error));
        return Outbox.builder()
                .eventId(Outbox.EVENT_PREFIX + UUID.randomUUID())
                .eventType(Outbox.EventType.UNFOLLOWED)
                .aggregateId(actorId) // Feed cần dọn thuộc người vừa bỏ follow.
                .payload(payload)
                .status(Outbox.EventStatus.PENDING)
                .createdAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .build();
    }

    private User readUser(String key) {
        User user = userRepository.table().getItem(GetItemEnhancedRequest.builder()
                .key(k -> k.partitionValue(key)).consistentRead(true).build());
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        return user;
    }

    private boolean replayed(IdempotencyKey receipt) {
        IdempotencyKey existing = idempotencyKeyRepository.table().getItem(GetItemEnhancedRequest.builder()
                .key(k -> k.partitionValue(receipt.getIdemKey())).consistentRead(true).build());
        if (existing == null) {
            return false;
        }
        if (!receipt.getRequestHash().equals(existing.getRequestHash())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Idempotency-Key was already used with different content");
        }
        return true;
    }

    private static boolean retryable(TransactionCanceledException exception) {
        var reasons = exception.cancellationReasons();
        return reasons.stream().anyMatch(r -> "ConditionalCheckFailed".equals(r.code())
                        || "TransactionConflict".equals(r.code()))
                && reasons.stream().allMatch(r -> "None".equals(r.code())
                        || "ConditionalCheckFailed".equals(r.code()) || "TransactionConflict".equals(r.code()));
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(10L * attempt, 30L * attempt));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Follow/unfollow interrupted", exception);
        }
    }

    private static Expression condition(String expression) {
        return Expression.builder().expression(expression).build();
    }

    private static String userKey(String id) {
        if (id == null || id.isBlank() || User.USER_PREFIX.equals(id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid userId");
        }
        return id.startsWith(User.USER_PREFIX) ? id : User.USER_PREFIX + id;
    }

    private static String hash(String request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(request.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
