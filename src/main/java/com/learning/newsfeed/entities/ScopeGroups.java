package com.learning.newsfeed.entities;

import java.util.Set;

public final class ScopeGroups {

    public static final Set<String> MEMBER = Set.of(
            Scopes.Posts.READ,
            Scopes.Posts.WRITE,
            Scopes.Users.READ,
            Scopes.Follows.WRITE
    );
}
