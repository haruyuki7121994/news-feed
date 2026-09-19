package com.learning.newsfeed.utils;

import io.vavr.control.Try;
import lombok.experimental.UtilityClass;
import tools.jackson.databind.ObjectMapper;

@UtilityClass
public class MapperUtil {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Try<String> toJson(Object value) {
        return Try.of(() -> objectMapper.writeValueAsString(value));
    }
}
