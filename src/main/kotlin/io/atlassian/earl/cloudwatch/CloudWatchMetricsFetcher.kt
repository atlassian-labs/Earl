package io.atlassian.earl.cloudwatch

import com.amazonaws.regions.Regions
import com.amazonaws.services.cloudwatch.AmazonCloudWatch
import com.amazonaws.services.cloudwatch.model.Dimension
import com.amazonaws.services.cloudwatch.model.GetMetricDataRequest
import com.amazonaws.services.cloudwatch.model.Metric
import com.amazonaws.services.cloudwatch.model.MetricDataQuery
import com.amazonaws.services.cloudwatch.model.MetricStat
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.joda.time.DateTime
import org.springframework.stereotype.Component
import java.io.File
import java.time.Instant

@Component
class CloudWatchMetricsFetcher(
    private val cloudWatchClient: Map<Regions, AmazonCloudWatch>
) {
    fun getMetricsForTable(
        metricName: String,
        region: Regions,
        tableName: String
    ) {
        val client = cloudWatchClient[region]!!

        val now = DateTime.now()

        val metricsResult = client.getMetricData(
            GetMetricDataRequest()
                .withMetricDataQueries(
                    MetricDataQuery()
                        .withId("consumed")
                        .withMetricStat(
                            MetricStat()
                                .withMetric(
                                    Metric()
                                        .withNamespace("AWS/DynamoDB")
                                        .withMetricName(metricName)
                                        .withDimensions(
                                            Dimension()
                                                .withName("TableName")
                                                .withValue(tableName)
                                        )
                                )
                                .withPeriod(60)
                                .withStat("Sum")
                        )
                )
                .withStartTime(now.minusDays(7).toDate())
                .withEndTime(now.toDate())
        )

        val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
        metricsResult.metricDataResults.forEach {
            it.timestamps
                .zip(it.values)
                .map { (timestamp, value) ->
                    Point(timestamp.toInstant(), value.toDouble() / 60.0)
                }
                .let { values -> File("out.json").writeText(objectMapper.writeValueAsString(values)) }
        }
    }
}

data class Point(
    val time: Instant,
    val value: Double
)
