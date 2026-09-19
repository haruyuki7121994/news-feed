package com.learning.newsfeed.dto.req;

import com.learning.newsfeed.entities.Post;
import com.learning.newsfeed.entities.User;
import com.learning.newsfeed.utils.TimeUtil;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.learning.newsfeed.entities.Post.POST_BY_AUTHOR_PREFIX;
import static com.learning.newsfeed.entities.Post.POST_PREFIX;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatePostDTO {

    @NotBlank(message = "Text is required")
    private String text;
    private String userId;
    private String idempotencyKey;
    private List<String> medias;

    public static Post toNewEntity(CreatePostDTO dto, String userId) {
        LocalDateTime now = LocalDateTime.now();
        String nowYYYYMM = TimeUtil.formatYYYYMM(now).getOrElseThrow(e -> new RuntimeException(e));
        String nowTime = TimeUtil.formatYYYYMMDDHHMMSS(now).getOrElseThrow(e -> new RuntimeException(e));
        String postId = POST_PREFIX + UUID.randomUUID();
        return Post.builder()
                .postId(postId)
                .authorId(userId)
                .text(dto.getText())
                .mediaIds(dto.getMedias())
                .createdAt(now)
                .postByAuthorPk(String.format("%s#%s", POST_BY_AUTHOR_PREFIX + userId, nowYYYYMM))
                .postByAuthorSk(String.format("%s#%s", nowTime, postId))
                .build();
    }
}
