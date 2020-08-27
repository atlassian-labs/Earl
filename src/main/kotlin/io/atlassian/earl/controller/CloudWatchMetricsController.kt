package io.atlassian.earl.controller

import com.amazonaws.regions.Regions
import io.atlassian.earl.cloudwatch.CloudWatchMetricsFetcher
import javafx.scene.chart.XYChart
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
    ): CloudWatchDataResult {
        val data = cloudWatchMetricsFetcher.getMetricsForTable(
            indexName = indexName,
            metricName = metric,
            region = region,
            tableName = tableName
        )

        return CloudWatchDataResult(
            data = data.map { XYChart.Data(it.time.toEpochMilli(), it.value) },
            lower = data.first().time.toEpochMilli().toDouble(),
            upper = data.last().time.toEpochMilli().toDouble()
        )
    }
}

data class CloudWatchDataResult(
    val data: List<XYChart.Data<Number, Number>>,
    val lower: Double,
    val upper: Double
)
