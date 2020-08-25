package io.atlassian.earl.controller

import com.amazonaws.regions.Regions
import io.atlassian.earl.cloudwatch.CloudWatchMetricsFetcher
import javafx.collections.ObservableList
import javafx.scene.chart.NumberAxis
import javafx.scene.chart.XYChart
import org.springframework.stereotype.Component
import java.time.Duration

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
        val data = cloudWatchMetricsFetcher.getMetricsForTable(
            metricName = metric,
            region = region,
            tableName = tableName
        )

        seriesData.setAll(
            data.map { XYChart.Data(it.time.toEpochMilli(), it.value) }
        )

        chartAxis.lowerBound = data.first().time.toEpochMilli().toDouble()
        chartAxis.upperBound = data.last().time.toEpochMilli().toDouble()
    }
}
