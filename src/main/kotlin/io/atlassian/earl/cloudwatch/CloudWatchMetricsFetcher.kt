package io.atlassian.earl.cloudwatch

import com.amazonaws.regions.Regions
import com.amazonaws.services.cloudwatch.AmazonCloudWatch
import com.amazonaws.services.cloudwatch.model.Dimension
import com.amazonaws.services.cloudwatch.model.GetMetricDataRequest
import com.amazonaws.services.cloudwatch.model.Metric
import com.amazonaws.services.cloudwatch.model.MetricDataQuery
import com.amazonaws.services.cloudwatch.model.MetricStat
import org.joda.time.DateTime
import org.springframework.stereotype.Component
import java.lang.IllegalArgumentException
import java.time.Instant

@Component
class CloudWatchMetricsFetcher(
    private val cloudWatchClient: Map<Regions, AmazonCloudWatch>
) {
    fun getMetricsForTable(
        indexName: String?,
        metricName: String,
        region: Regions,
        tableName: String
    ): List<Point> {
        val client = cloudWatchClient[region]!!

        val now = DateTime.now()

        val dimensions = listOfNotNull(
            Dimension()
                .withName("TableName")
                .withValue(tableName),
            indexName?.let {
                Dimension()
                    .withName("GlobalSecondaryIndexName")
                    .withValue(it)
            }
        )

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
                                        .withDimensions(dimensions)
                                )
                                .withPeriod(60)
                                .withStat("Sum")
                        )
                        .withReturnData(false),
                    MetricDataQuery()
                        .withId("consumedPerSecond")
                        .withExpression("consumed/60")
                        .withReturnData(true)
                )
                .withStartTime(now.minusDays(7).toDate())
                .withEndTime(now.toDate())
        )

        if (metricsResult.metricDataResults.first().timestamps.isEmpty()) {
            throw IllegalArgumentException("No cloudwatch data found for $tableName, $region, gsi=$indexName")
        }

        return metricsResult.metricDataResults.first()
            .let { it.timestamps.zip(it.values) }
            .map { (timestamp, value) -> Point(timestamp.toInstant(), value.toDouble()) }
            .sortedBy { it.time }
            .let { it.subList(0, it.size - 1) } // Last data point is probably incomplete
    }
}

data class Point(
    val time: Instant,
    val value: Double
)
