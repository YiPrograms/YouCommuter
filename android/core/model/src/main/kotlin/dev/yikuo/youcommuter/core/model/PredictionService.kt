package dev.yikuo.youcommuter.core.model

/** Blank endpoint disables prediction without affecting station inventory. */
data class PredictionServiceConfiguration(val endpoint: String = "", val apiKey: String = "") {
    override fun toString() = "PredictionServiceConfiguration(endpoint=$endpoint)"
}
