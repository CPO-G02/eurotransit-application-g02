package it.polito.eurotransit.orders

import it.polito.eurotransit.orders.config.JwtAudienceValidator
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Instant

class JwtAudienceValidatorTest {

    @Test
    fun `accepts token with expected audience`() {
        val result = JwtAudienceValidator("orders").validate(jwtWithAudience("orders"))

        assertFalse(result.hasErrors())
    }

    @Test
    fun `rejects token without expected audience`() {
        val result = JwtAudienceValidator("orders").validate(jwtWithAudience("inventory"))

        assertTrue(result.hasErrors())
    }

    private fun jwtWithAudience(audience: String): Jwt {
        return Jwt.withTokenValue("token")
            .header("alg", "none")
            .audience(listOf(audience))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build()
    }
}
