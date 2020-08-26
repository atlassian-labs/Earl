package io.atlassian.earl.controller

import com.amazonaws.regions.Regions
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.atlassian.earl.cloudwatch.CloudWatchMetricsFetcher
import io.atlassian.earl.cloudwatch.Point
import javafx.collections.ObservableList
import javafx.scene.chart.NumberAxis
import javafx.scene.chart.XYChart
import org.springframework.stereotype.Component
import java.io.File

@Component
class CloudWatchMetricsController(
    private val cloudWatchMetricsFetcher: CloudWatchMetricsFetcher
) {
    fun fillOutCloudWatchData(
        chartAxis: NumberAxis,
        metric: String,
        region: Regions,
        seriesData: ObservableList<XYChart.Data<Number, Number>>,
        tableName: String,
    ) {
//        val data = cloudWatchMetricsFetcher.getMetricsForTable(
//            metricName = metric,
//            region = region,
//            tableName = tableName
//        )

        val data = jacksonObjectMapper().registerModule(JavaTimeModule())
            .readValue<List<Point>>(File("out.json").reader())
            .sortedBy { it.time }


        seriesData.setAll(
            data.map { XYChart.Data(it.time.toEpochMilli(), it.value) }
        )

        chartAxis.lowerBound = data.first().time.toEpochMilli().toDouble()
        chartAxis.upperBound = data.last().time.toEpochMilli().toDouble()
    }
}
