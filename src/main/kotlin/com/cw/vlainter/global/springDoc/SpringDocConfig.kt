package com.cw.vlainter.global.springDoc

import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType
import io.swagger.v3.oas.annotations.info.Info
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.security.SecurityScheme
import org.springdoc.core.models.GroupedOpenApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration


@Configuration
@OpenAPIDefinition(
    info = Info(title = "Vlainter API", version = "v1", description = "API 문서"),
    security = [SecurityRequirement(name = "bearerAuth")]
)
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, bearerFormat = "JWT", scheme = "bearer")
class SpringDocConfig {
    @Bean
    fun groupApiV1() = GroupedOpenApi.builder()
        .group("api")
        .pathsToMatch("/api/**")
        .build()
}