package io.atlassian.earl.cloudwatch

import com.amazonaws.regions.Regions
import com.amazonaws.services.cloudwatch.AmazonCloudWatch
import com.amazonaws.services.cloudwatch.model.Dimension
import com.amazonaws.services.cloudwatch.model.GetMetricDataResult
import com.amazonaws.services.cloudwatch.model.MetricDataResult
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.argThat
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Duration
import java.time.Instant
import java.util.Date

@ExtendWith(MockitoExtension::class)
class CloudWatchMetricsFetcherTest {

    lateinit var cloudWatchMetricsFetcher: CloudWatchMetricsFetcher

    @Mock
    lateinit var cloudWatch: AmazonCloudWatch

    lateinit var cloudWatchClients: Map<Regions, AmazonCloudWatch>

    @BeforeEach
    fun setUp() {
        cloudWatchClients = mapOf(Regions.US_EAST_1 to cloudWatch)
        cloudWatchMetricsFetcher = CloudWatchMetricsFetcher(cloudWatchClients)
    }

    @Test
    fun `Fetcher will retrieve a set of points for the given input`() {
        whenever(cloudWatch.getMetricData(any())).thenReturn(
            GetMetricDataResult().withMetricDataResults(TEST_CW_DATA)
        )

        val points = cloudWatchMetricsFetcher.getMetricsForTable(
            indexName = "index-name",
            tableName = "table-name",
            metricName = "WriteCapacityUnits",
            region = Regions.US_EAST_1
        )

        assertThat(points).containsExactlyElementsOf(TEST_POINTS.slice(0 until TEST_POINTS.size - 1))
    }

    @Nested
    inner class SideEffectTests {
        @BeforeEach
        fun setUp() {
            whenever(cloudWatch.getMetricData(any())).thenReturn(
                GetMetricDataResult().withMetricDataResults(TEST_CW_DATA)
            )
        }

        @Test
        fun `Fetch will get get data for the correct table`() {
            cloudWatchMetricsFetcher.getMetricsForTable(
                indexName = null,
                tableName = "table-name",
                metricName = "WriteCapacityUnits",
                region = Regions.US_EAST_1
            )

            verify(cloudWatch).getMetricData(
                argThat {
                    val query = metricDataQueries.first { it.id == "consumed" }
                    query.metricStat.metric.namespace == "AWS/DynamoDB" &&
                            query.metricStat.metric.dimensions == listOf(
                        Dimension()
                            .withName("TableName")
                            .withValue("table-name")
                    )
                }
            )
        }

        @Test
        fun `Fetch will get get data for the correct table and index`() {
            cloudWatchMetricsFetcher.getMetricsForTable(
                indexName = "index-name",
                tableName = "table-name",
                metricName = "WriteCapacityUnits",
                region = Regions.US_EAST_1
            )

            verify(cloudWatch).getMetricData(
                argThat {
                    val query = metricDataQueries.first { it.id == "consumed" }

                    query.metricStat.metric.dimensions == listOf(
                        Dimension()
                            .withName("TableName")
                            .withValue("table-name"),
                        Dimension()
                            .withName("GlobalSecondaryIndexName")
                            .withValue("index-name")
                    )
                }
            )
        }

        @Test
        fun `Fetch will include a query to get the results per minute`() {
            cloudWatchMetricsFetcher.getMetricsForTable(
                indexName = null,
                tableName = "table-name",
                metricName = "WriteCapacityUnits",
                region = Regions.US_EAST_1
            )

            verify(cloudWatch).getMetricData(
                argThat {
                    val query = metricDataQueries.first { it.id == "consumed" }
                    val math = metricDataQueries.first { it.id == "consumedPerSecond" }

                    query.metricStat.metric.metricName == "WriteCapacityUnits" &&
                            query.metricStat.period == 60 &&
                            query.metricStat.stat == "Sum" &&
                            math.expression == "consumed/60"
                }
            )
        }

        @Test
        fun `Fetch will pull the last 7 days of data`() {
            cloudWatchMetricsFetcher.getMetricsForTable(
                indexName = null,
                tableName = "table-name",
                metricName = "WriteCapacityUnits",
                region = Regions.US_EAST_1
            )

            verify(cloudWatch).getMetricData(
                argThat {
                    startTime == endTime.toInstant().minus(Duration.ofDays(7)).let { Date(it.toEpochMilli()) }
                }
            )
        }
    }

    @Nested
    inner class InvalidInputs {
        @Test
        fun `Fetch will fail if the client is not initliased`() {
            assertThatThrownBy {
                cloudWatchMetricsFetcher.getMetricsForTable(
                    indexName = null,
                    tableName = "table-name",
                    metricName = "WriteCapacityUnits",
                    region = Regions.US_EAST_2
                )
            }.isInstanceOf(IllegalStateException::class.java)
        }

        @Test
        fun `Fetch will fail if no data is returned`() {
            whenever(cloudWatch.getMetricData(any())).thenReturn(GetMetricDataResult())
            assertThatThrownBy {
                cloudWatchMetricsFetcher.getMetricsForTable(
                    indexName = null,
                    tableName = "table-name",
                    metricName = "WriteCapacityUnits",
                    region = Regions.US_EAST_1
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    companion object {
        private val TEST_POINTS = listOf(
            Point(Instant.ofEpochSecond(1L), 1.0),
            Point(Instant.ofEpochSecond(2L), 2.0),
            Point(Instant.ofEpochSecond(3L), 3.0),
            Point(Instant.ofEpochSecond(4L), 4.0),
            Point(Instant.ofEpochSecond(5L), 5.0),
            Point(Instant.ofEpochSecond(6L), 0.0), // The final data point will be omitted in CW since it is incomplete
        )

        private val TEST_CW_DATA = MetricDataResult().withId("consumed")
            .withTimestamps(TEST_POINTS.map { Date(it.time.toEpochMilli()) })
            .withValues(TEST_POINTS.map { it.value })
    }
}
