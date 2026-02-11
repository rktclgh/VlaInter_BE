package com.cw.vlainter

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaAuditing

@SpringBootApplication
@EnableJpaAuditing
class VlainterApplication

fun main(args: Array<String>) {
	runApplication<VlainterApplication>(*args)
}
