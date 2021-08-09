package io.atlassian.earl.controller

import com.amazonaws.regions.Regions
import io.atlassian.earl.cloudwatch.CloudWatchMetricsFetcher
import io.atlassian.earl.cloudwatch.Point
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.whenever
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class CloudWatchMetricsControllerTest {
    private lateinit var controller: CloudWatchMetricsController

    @Mock
    private lateinit var cloudWatchMetricsFetcher: CloudWatchMetricsFetcher

    private val indexName = "index-name"
    private val metric = "ConsumedWriteCapacityUnits"
    private val region = Regions.AP_SOUTHEAST_2
    private val tableName = "my-funky-table"

    @BeforeEach
    fun setUp() {
        controller = CloudWatchMetricsController(cloudWatchMetricsFetcher)
    }

    @Test
    fun `Fetching metrics will retrieve metrics from cloudwatch`() {
        val expectedResult = listOf(
            Point(Instant.ofEpochMilli(50001), 1.0),
            Point(Instant.ofEpochMilli(50002), 2.0),
            Point(Instant.ofEpochMilli(50000), 0.0),
        )

        whenever(cloudWatchMetricsFetcher.getMetricsForTable(indexName, metric, region, tableName))
            .thenReturn(expectedResult)

        val result = controller.fillOutCloudWatchData(indexName, metric, region, tableName)

        assertThat(result.data).containsExactlyElementsOf(expectedResult.map { it.time.toEpochMilli() to it.value })
        assertThat(result.lower).isEqualTo(50000.0)
        assertThat(result.upper).isEqualTo(50002.0)
    }
}
