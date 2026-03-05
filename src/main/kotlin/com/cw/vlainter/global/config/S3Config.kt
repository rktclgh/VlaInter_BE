package com.cw.vlainter.global.config

import com.cw.vlainter.global.config.properties.S3Properties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI

@Configuration
class S3Config(
    private val s3Properties: S3Properties
) {
    @Bean
    fun s3Client(): S3Client {
        val builder = S3Client.builder()
            .region(Region.of(s3Properties.region.trim()))

        if (s3Properties.endpoint.isNotBlank()) {
            builder.endpointOverride(URI.create(s3Properties.endpoint.trim()))
        }

        return builder.build()
    }
}
