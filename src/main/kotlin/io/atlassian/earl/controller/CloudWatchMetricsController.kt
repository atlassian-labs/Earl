package io.atlassian.earl.controller

import com.amazonaws.regions.Regions
import io.atlassian.earl.cloudwatch.CloudWatchMetricsFetcher
import org.springframework.stereotype.Component

@Component
class CloudWatchMetricsController(
    private val cloudWatchMetricsFetcher: CloudWatchMetricsFetcher
) {
    fun fillOutCloudWatchData(
        indexName: String?,
        metric: String,
        region: Regions,
        tableName: String,
    ): ConsumedResourcesData {
        val data = cloudWatchMetricsFetcher.getMetricsForTable(
            indexName = indexName,
            metricName = metric,
            region = region,
            tableName = tableName
        )

        return ConsumedResourcesData(
            data = data.map { it.time.toEpochMilli() to it.value },
            lower = data.minOf { it.time }.toEpochMilli().toDouble(),
            upper = data.maxOf { it.time }.toEpochMilli().toDouble()
        )
    }
}

/**
 * Cloud watch usage data.
 *
 * @param data List of data points
 * @param lower Lower bound of the X-axis
 * @param upper Upper bound of the X-axis
 */
data class ConsumedResourcesData(
    val data: List<Pair<Number, Number>>,
    val lower: Double,
    val upper: Double
)
