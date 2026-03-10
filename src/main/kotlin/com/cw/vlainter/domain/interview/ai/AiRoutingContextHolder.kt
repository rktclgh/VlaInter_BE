package com.cw.vlainter.domain.interview.ai

import com.cw.vlainter.global.config.properties.AiProvider
import org.springframework.stereotype.Component

data class AiRoutingSnapshot(
    val usedProviders: List<AiProvider> = emptyList()
) {
    val providerUsed: AiProvider?
        get() = usedProviders.lastOrNull()

    val fallbackDepth: Int
        get() = (usedProviders.size - 1).coerceAtLeast(0)
}

@Component
class AiRoutingContextHolder {
    private data class RoutingState(
        val usedProviders: LinkedHashSet<AiProvider> = linkedSetOf(),
        var geminiExhausted: Boolean = false,
        var preferredGeminiModelIndex: Int = 0
    )

    private val context = ThreadLocal.withInitial { RoutingState() }

    fun reset() {
        context.set(RoutingState())
    }

    fun markUsed(provider: AiProvider) {
        context.get().usedProviders.add(provider)
    }

    fun markGeminiExhausted() {
        context.get().geminiExhausted = true
    }

    fun isGeminiExhausted(): Boolean {
        return context.get().geminiExhausted
    }

    fun preferredGeminiModelIndex(): Int {
        return context.get().preferredGeminiModelIndex
    }

    fun promoteGeminiModelIndex(nextIndex: Int) {
        val state = context.get()
        state.preferredGeminiModelIndex = maxOf(state.preferredGeminiModelIndex, nextIndex)
    }

    fun snapshot(): AiRoutingSnapshot {
        return AiRoutingSnapshot(usedProviders = context.get().usedProviders.toList())
    }

    fun clear() {
        context.remove()
    }
}
