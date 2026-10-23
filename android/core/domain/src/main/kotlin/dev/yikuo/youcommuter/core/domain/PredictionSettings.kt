package dev.yikuo.youcommuter.core.domain

import dev.yikuo.youcommuter.core.model.PredictionServiceConfiguration

interface PredictionSettings {
    fun load(): PredictionServiceConfiguration
    val revision: Long
    suspend fun save(configuration: PredictionServiceConfiguration)
}
