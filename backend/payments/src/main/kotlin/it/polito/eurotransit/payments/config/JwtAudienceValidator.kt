package it.polito.eurotransit.payments.config

import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt

class JwtAudienceValidator(
    private val expectedAudience: String,
) : OAuth2TokenValidator<Jwt> {

    override fun validate(token: Jwt): OAuth2TokenValidatorResult {
        if (token.audience?.contains(expectedAudience) == true) {
            return OAuth2TokenValidatorResult.success()
        }
        return OAuth2TokenValidatorResult.failure(
            OAuth2Error("invalid_token", "JWT audience must contain $expectedAudience", null),
        )
    }
}
