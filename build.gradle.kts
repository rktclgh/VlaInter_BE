plugins {
	kotlin("jvm") version "1.9.25"
	kotlin("plugin.spring") version "1.9.25"
	id("org.springframework.boot") version "3.5.10"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "1.9.25"
}

group = "com.cw"
version = "0.0.1-SNAPSHOT"
description = "BackEnd Server For VlanInter"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-mail")
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.flywaydb:flyway-core")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("software.amazon.awssdk:s3:2.33.0") {
		exclude(group = "commons-logging", module = "commons-logging")
	}
	implementation("software.amazon.awssdk:bedrockruntime:2.33.0") {
		exclude(group = "commons-logging", module = "commons-logging")
	}
	implementation("org.apache.pdfbox:pdfbox:2.0.32") {
		exclude(group = "commons-logging", module = "commons-logging")
	}
	implementation("org.apache.poi:poi-ooxml:5.5.1") {
		exclude(group = "commons-logging", module = "commons-logging")
	}
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.apache.commons:commons-lang3:3.18.0")
	implementation("io.jsonwebtoken:jjwt-api:0.12.6")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("io.github.thoroldvix:youtube-transcript-api:0.4.0")
	developmentOnly("org.springframework.boot:spring-boot-devtools")
	runtimeOnly("com.h2database:h2")
	runtimeOnly("org.postgresql:postgresql")
	runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
	runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Swagger UI 및 OpenAPI 3.0 문서화
    implementation("io.swagger.core.v3:swagger-annotations:2.2.34")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.9")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
	useJUnitPlatform()
	systemProperty("spring.datasource.url", "jdbc:h2:mem:vlainter-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
	systemProperty("spring.datasource.driver-class-name", "org.h2.Driver")
	systemProperty("spring.datasource.username", "sa")
	systemProperty("spring.datasource.password", "")
	systemProperty("spring.jpa.hibernate.ddl-auto", "create-drop")
	systemProperty("spring.jpa.show-sql", "false")
	systemProperty("spring.flyway.enabled", "false")
}
