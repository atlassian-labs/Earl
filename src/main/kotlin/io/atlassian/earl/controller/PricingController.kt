package io.atlassian.earl.controller

import io.atlassian.earl.cloudwatch.Point
import org.springframework.stereotype.Component

@Component
class PricingController {
    fun calculateProvisionedCapacityCost(cuPrice: Double, data: List<Point>) =
        data.groupBy { it.time.toEpochMilli() / ONE_HOUR_IN_MILLIS } // Int division to bucket into hours
            .mapValues { (_, values) -> values.maxOf { it.value } }
            .mapValues { (_, cu) -> cu * cuPrice }
            .values
            .sum()

    fun calculateOnDemandCost(cuPricePerMillion: Double, usageData: List<Double>) =
        usageData.sumOf { it * 60.0 } / 1_000_000 * cuPricePerMillion

    companion object {
        private const val ONE_HOUR_IN_MILLIS = 60 * 60 * 1000
    }
}
