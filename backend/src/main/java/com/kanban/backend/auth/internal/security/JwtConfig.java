package com.kanban.backend.auth.internal.security;

import com.kanban.backend.auth.internal.config.AuthProperties;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
public class JwtConfig {

    @Bean
    public RSAKey rsaKey(AuthProperties properties, ResourceLoader resources) throws IOException {
        try (var publicInput = resources.getResource(properties.getPublicKeyLocation()).getInputStream();
            var privateInput = resources.getResource(properties.getPrivateKeyLocation()).getInputStream()) {
            var publicKey = RsaKeyConverters.x509().convert(publicInput);
            var privateKey = RsaKeyConverters.pkcs8().convert(privateInput);
            if (publicKey.getModulus().bitLength() < 2048
                    || !publicKey.getModulus().equals(privateKey.getModulus())) {
                throw new IllegalArgumentException("JWT requires a matching RSA key pair of at least 2048 bits.");
            }
            return new RSAKey.Builder(publicKey).privateKey(privateKey).build();
        }
    }

    @Bean
    public JwtEncoder jwtEncoder(RSAKey rsaKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));
    }

    @Bean
    public JwtDecoder jwtDecoder(RSAKey rsaKey, AuthProperties properties, Clock clock) throws Exception {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(properties.getClockSkew());
        timestamps.setClock(clock);
        timestamps.setAllowEmptyExpiryClaim(false);

        OAuth2TokenValidator<Jwt> timeValidator = jwt -> {
            if (jwt.getExpiresAt() != null
                    && clock.instant().minus(properties.getClockSkew()).isAfter(jwt.getExpiresAt())) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("ACCESS_TOKEN_EXPIRED"));
            }
            return timestamps.validate(jwt);
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timeValidator,
                new JwtIssuerValidator(properties.getIssuer()),
                new JwtAudienceValidator(properties.getAudience()),
                new JwtClaimValidator<Instant>("iat", issuedAt ->
                        !issuedAt.isAfter(clock.instant().plus(properties.getClockSkew()))),
                new JwtClaimValidator<String>("sub", subject -> {
                    try {
                        return UUID.fromString(subject).toString().equals(subject);
                    } catch (IllegalArgumentException exception) {
                        return false;
                    }
                })));
        return decoder;
    }
}
