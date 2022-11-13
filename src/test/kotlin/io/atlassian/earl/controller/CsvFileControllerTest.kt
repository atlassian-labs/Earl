package io.atlassian.earl.controller

import io.atlassian.earl.csvfile.CsvFileFetcher
import io.atlassian.earl.csvfile.Record
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.BeforeEach

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.time.Instant

@ExtendWith(MockitoExtension::class)
internal class CsvFileControllerTest {

    @Mock
    private lateinit var csvFileFetcher: CsvFileFetcher

    @Mock
    private lateinit var mockFile: File

    private lateinit var csvFileController: CsvFileController

    private lateinit var testData: List<Record>

    @BeforeEach
    fun setUp() {
        csvFileController = CsvFileController(csvFileFetcher)

        testData = listOf(
            Record(time = Instant.ofEpochSecond(1), value = 42.0),
            Record(time = Instant.ofEpochSecond(2), value = 69.0),
        )

        whenever(csvFileFetcher.getUsageData(mockFile)).thenReturn(testData)
    }

    @Test
    fun `getData should return the data correctly`() {
        val result = csvFileController.getData(mockFile)
        assertThat(result.data).contains(1000L to 42.0, 2000L to 69.0)
    }

    @Test
    fun `getData should return the min value correctly`() {
        val result = csvFileController.getData(mockFile)
        assertThat(result.lower).isCloseTo(1000.0, Offset.offset(0.001))
    }

    @Test
    internal fun `getData should return the max value correctly`() {
        val result = csvFileController.getData(mockFile)
        assertThat(result.upper).isCloseTo(2000.0, Offset.offset(0.001))
    }
}