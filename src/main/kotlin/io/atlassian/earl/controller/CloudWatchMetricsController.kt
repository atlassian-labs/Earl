package io.atlassian.earl.controller

import com.amazonaws.regions.Regions
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.atlassian.earl.cloudwatch.CloudWatchMetricsFetcher
import io.atlassian.earl.cloudwatch.Point
import javafx.scene.chart.XYChart
import org.springframework.stereotype.Component
import java.io.File

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

//        val data = jacksonObjectMapper().registerModule(JavaTimeModule())
//            .readValue<List<Point>>(File("out.json").reader())
//            .sortedBy { it.time }

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
