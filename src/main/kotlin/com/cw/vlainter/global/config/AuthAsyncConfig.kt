package com.cw.vlainter.global.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

@Configuration
class AuthAsyncConfig {
    @Bean(name = ["authAuditExecutor"])
    fun authAuditExecutor(): Executor {
        return ThreadPoolTaskExecutor().apply {
            corePoolSize = 1
            maxPoolSize = 2
            queueCapacity = 200
            setThreadNamePrefix("auth-audit-")
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(10)
            initialize()
        }
    }

    @Bean(name = ["academicSearchWarmExecutor"])
    fun academicSearchWarmExecutor(): Executor {
        return ThreadPoolTaskExecutor().apply {
            corePoolSize = 1
            maxPoolSize = 2
            queueCapacity = 50
            setThreadNamePrefix("academic-search-warm-")
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(10)
            initialize()
        }
    }
}
