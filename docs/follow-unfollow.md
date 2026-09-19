# Follow/Unfollow với idempotency và optimistic locking

Hai handler gọi chung `FollowRelationshipService.setFollowing(...)` để dùng cùng
quy tắc tạo key, transaction và retry. Các bước chính được comment trong service.

## Quan hệ A follow B

```text
followers.PK     = USER#B
followers.SK     = FOLLOWER#USER#A
followers.GSI1PK = FOLLOWER#USER#A
followers.GSI1SK = USER#B
```

Consumer fanout query `PK=USER#B` để tìm follower của author B. Khi bỏ prefix
`FOLLOWER#` từ SK, consumer nhận `USER#A`, khớp key Redis `userFeeds:USER#A:latest`.
API chấp nhận target ID có hoặc chưa có prefix `USER#` và chuẩn hóa một lần.
Actor luôn lấy từ authentication context. Self-follow/self-unfollow bị từ chối.

## Transaction thay đổi quan hệ

| Index | Follow | Unfollow |
|---|---|---|
| 0 | Put idempotency, chỉ khi key chưa tồn tại | Put idempotency, chỉ khi key chưa tồn tại |
| 1 | Put quan hệ, chỉ khi quan hệ chưa tồn tại | Delete quan hệ, chỉ khi quan hệ tồn tại |
| 2 | Put User với count tăng 1 và version cũ khớp | Put User với count giảm 1 và version cũ khớp |
| 3 | Không có | Put outbox `UNFOLLOWED`, chỉ khi eventId chưa tồn tại |

Giữ `@DynamoDbVersionAttribute` trên `User.version`. Enhanced Client tự thêm
điều kiện version vào Put và tăng version của item được ghi. Điều kiện User
còn tồn tại được kết hợp để không tạo lại User vừa bị xóa. Mọi thay đổi trong
transaction cùng thành công hoặc cùng bị hủy.

Unfollow thực sự xóa quan hệ sẽ tạo outbox trong cùng transaction và lưu
`eventId` vào receipt idempotency. EventId được chuẩn bị một lần và dùng lại
qua các lượt retry version. Lỗi ghi outbox làm cả transaction bị hủy; request
retry cùng key sau khi thành công không tạo outbox thứ hai. Follow và Unfollow
no-op không tạo event `UNFOLLOWED`.

Nếu trạng thái đã đúng, transaction vẫn tạo idempotency ở index 0, nhưng index
1 chỉ kiểm tra quan hệ đang tồn tại/không tồn tại; index 2 kiểm tra User tồn tại.
Không cập nhật count hay version cho trường hợp này. Lưu cả request không làm
thay đổi dữ liệu giúp retry request cũ sau thao tác ngược chiều không tác động
đến quan hệ hiện tại.

## Request gửi lại và xung đột

Key idempotency có dạng `FOLLOW#USER#A#clientKey` hoặc
`UNFOLLOW#USER#A#clientKey`. Hash gồm thao tác, actor và target. Cùng thao tác,
actor và key nhưng target khác trả `409 Conflict`. Key hợp lệ gồm 1–128 ký tự
chữ/số/`_`/`-`; timestamp TTL của receipt là 24 giờ sau khi tạo. Code giữ nguyên
receipt còn tồn tại, kể cả khi đã qua TTL nhưng DynamoDB chưa xóa.

Idempotency được kiểm tra bằng consistent read trước khi đọc quan hệ. Do đó
Unfollow retry vẫn trả kết quả thành công sau khi quan hệ đã bị xóa. Response
trả các định danh quan hệ ổn định; `createdAt` chỉ được ghi trên quan hệ lưu
trong DynamoDB, không dùng làm kết quả thay đổi giữa lần đầu và replay.

Khi transaction bị hủy, service kiểm tra lỗi ở index 0 trước. Nếu receipt đã
tồn tại và hash khớp thì trả kết quả thành công, kể cả khi index 2 cũng báo lỗi
version. Các lỗi điều kiện do race và `TransactionConflict` được retry tối đa
3 lượt tổng cộng với backoff ngắn có jitter. Mỗi lượt đọc lại User/version và
quan hệ bằng consistent read. Hết lượt trả `409`, client có thể gửi lại cùng
key. Các lỗi khác không được coi là request trùng thành công.

## Dữ liệu cũ và phạm vi thay đổi

- Code này không tự sửa quan hệ cũ có PK `USER#USER#...`, quan hệ đảo chiều
  hoặc count đã bị cộng sai. Cần đối soát các item cũ và tính lại count trước
  khi dùng chúng để kiểm tra fanout.
- Scope/key/hash của receipt Follow/Unfollow đã thay đổi so với handler cũ;
  receipt cũ không được tự động di chuyển. Không bảo đảm replay request từ
  phiên bản cũ; tránh replay qua thời điểm nâng cấp hoặc di chuyển receipt
  theo quy tắc riêng nếu cần giữ cam kết đó.
- Lỗi count không hợp lệ được trả `409`; service không tự giảm count xuống âm.
- Chưa tạo outbox cho Follow; Unfollow đã tạo `UNFOLLOWED` để worker sau này
  dọn các bài của author khỏi Redis feed (xem contract bên dưới).

## Kiểm thử

Chạy `./mvnw test`. `FollowRelationshipServiceTest` dùng Enhanced Client thật
(bao gồm extension version), còn transport DynamoDB được mock. Test kiểm tra
request SDK sinh ra và mô phỏng cancellation để kiểm tra replay/retry, không
thay thế kiểm thử tích hợp trên DynamoDB.

Các trường hợp bao gồm: key đúng chiều; request trùng; key mới cho trạng thái
đã đúng; Unfollow retry sau khi xóa; idempotency và version cùng lỗi; retry với
version mới; hai request cùng thay đổi một quan hệ; race với request ngược
chiều; User cũ chưa có version; hết lượt retry và lỗi hệ thống. Các test outbox
kiểm tra payload đúng actor/author, eventId giữ nguyên khi retry, không tạo event
trùng/no-op, và transaction lỗi ghi outbox không được báo thành công.

## Outbox UNFOLLOWED để dọn feed

Ví dụ A unfollow B, outbox có `status=PENDING`, `eventType=UNFOLLOWED`,
`aggregateId=USER#A` (chủ feed cần dọn) và `payload` chứa chuỗi JSON:

```json
{
  "followerId": "USER#A",
  "authorId": "USER#B",
  "unfollowedAt": "2026-09-20T03:00:00Z"
}
```

`unfollowedAt` và `outbox.createdAt` biểu diễn cùng thời điểm UTC chuẩn bị event,
không phải timestamp commit hoặc một version dùng để đảm bảo thứ tự xử lý.
Outbox không đặt `expiresAt` ngay lúc tạo để không tự hết hạn khi chưa được xử lý.

Producer stream hiện có publish được event này lên Kafka. **Consumer/worker
hiện tại chỉ xử lý `POST_CREATED`, chưa xử lý dọn feed cho `UNFOLLOWED`.** Vì
vậy thêm outbox chưa tự xóa dữ liệu Redis. Bước tiếp theo là route `UNFOLLOWED`
sang task cleanup; nếu event đã bị consumer cũ bỏ qua thì cần replay để xử lý.

Cleanup cần tìm các postId của author B đang nằm trong feed của A rồi `ZREM`
chúng khỏi `userFeeds:USER#A:latest` và `userFeeds:USER#A:ranked`. Không xóa Post
gốc hoặc xóa cả feed của A. Redis hiện chỉ lưu postId/score, nên worker cần
tra author của Post hoặc có index theo author để lọc chính xác.

Khi triển khai worker cleanup phải xử lý cả task fanout cũ đến muộn (có thể ghi
lại bài vừa dọn) và việc A follow lại B trước khi event cleanup được xử lý.
Payload này chưa cung cấp version quan hệ/fencing để giải quyết các race đó.
