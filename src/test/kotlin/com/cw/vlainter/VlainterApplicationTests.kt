package com.cw.vlainter

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(
    properties = [
        "app.jwt.access-secret=test-access-secret-1234567890123456",
        "app.jwt.refresh-secret=test-refresh-secret-123456789012345",
        "app.security.api-key.encryption-secret=test-api-key-secret",
        "spring.mail.host=localhost",
        "app.portone.base-url=https://api.portone.io",
        "app.portone.api-key=test-portone-api-key",
        "app.portone.api-secret=test-portone-api-secret",
        "app.portone.customer-code=test-customer-code",
        "app.redirect.allowed-origins=http://localhost:5173",
        "app.cors.allowed-origins=http://localhost:5173"
    ]
)
class VlainterApplicationTests {

	@Test
	fun contextLoads() {
	}

}
