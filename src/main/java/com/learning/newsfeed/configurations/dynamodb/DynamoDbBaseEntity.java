package com.learning.newsfeed.configurations.dynamodb;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

public interface DynamoDbBaseEntity {

    default String getValue(String attr) {
        try {
            for (Method method : this.getClass().getMethods()) {
                if (method.getName().startsWith("get")) {
                    Annotation[] annotations = method.getAnnotations();
                    for (Annotation annotation : annotations) {
                        if (annotation instanceof DynamoDbAttribute attribute) {
                            if (attribute.value().equals(attr)) {
                                return (String) method.invoke(this);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
