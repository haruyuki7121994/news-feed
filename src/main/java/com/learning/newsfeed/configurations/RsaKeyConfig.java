package com.learning.newsfeed.configurations;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Objects;

@Configuration
public class RsaKeyConfig {

    @Bean
    public RSAPublicKey rsaPublicKey(
            @Value("${app.jwt.public-key}") Resource resource)
            throws IOException {

        try (InputStream input = resource.getInputStream()) {
            return Objects.requireNonNull(
                    RsaKeyConverters.x509().convert(input),
                    "Could not read RSA public key"
            );
        }
    }

    @Bean
    public RSAPrivateKey rsaPrivateKey(
            @Value("${app.jwt.private-key}") Resource resource)
            throws IOException {

        try (InputStream input = resource.getInputStream()) {
            return Objects.requireNonNull(
                    RsaKeyConverters.pkcs8().convert(input),
                    "Could not read RSA private key"
            );
        }
    }
}
