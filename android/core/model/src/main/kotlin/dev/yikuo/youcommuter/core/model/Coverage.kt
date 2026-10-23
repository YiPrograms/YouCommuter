package dev.yikuo.youcommuter.core.model

data class ServiceRegion(
    val code: String,
    val name: String,
    val status: CoverageStatus,
    val electricCounts: Boolean,
)

enum class CoverageStatus { NotConfigured, Collecting, Available, Degraded, Unavailable, Unknown }

sealed interface CoverageResult {
    data class Success(val regions: List<ServiceRegion>) : CoverageResult
    data class Failure(val reason: FailureReason) : CoverageResult
}

enum class FailureReason { NotConfigured, Network, Service, InvalidResponse }
