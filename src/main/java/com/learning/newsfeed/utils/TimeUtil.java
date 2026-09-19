package com.learning.newsfeed.utils;

import io.vavr.control.Try;
import lombok.experimental.UtilityClass;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@UtilityClass
public class TimeUtil {

    private static final String PATTERN_YYYYMMDD = "yyyy-MM-dd";
    private static final String PATTERN_YYYYMM = "yyyy-MM";
    private static final String PATTERN_YYYYMMDDHHMMSS = "yyyy-MM-dd'T'HH:mm:ss";

    public Try<String> formatByPattern(LocalDateTime time, String pattern) {
        return Try.of(() -> time.format(DateTimeFormatter.ofPattern(pattern)));
    }

    public Try<String> formatYYYYMMDD(LocalDateTime time) {
        return formatByPattern(time, PATTERN_YYYYMMDD);
    }

    public Try<String> formatYYYYMM(LocalDateTime time) {
        return formatByPattern(time, PATTERN_YYYYMM);
    }

    public Try<String> formatYYYYMMDDHHMMSS(LocalDateTime time) {
        return formatByPattern(time, PATTERN_YYYYMMDDHHMMSS);
    }
}
