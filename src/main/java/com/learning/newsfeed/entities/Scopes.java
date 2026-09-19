package com.learning.newsfeed.entities;

import lombok.NoArgsConstructor;

@NoArgsConstructor
public final class Scopes {

    @NoArgsConstructor
    public static final class Posts {
        public static final String READ = "posts:read";
        public static final String WRITE = "posts:write";
    }

    @NoArgsConstructor
    public static final class Users {
        public static final String READ = "users:read";
    }

    @NoArgsConstructor
    public static final class Follows {
        public static final String WRITE = "follows:write";
    }
}
