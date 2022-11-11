package io.atlassian.earl.csvfile

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.AutoCloseableSoftAssertions
import org.assertj.core.data.Offset
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class CsvFileFetcherTest {
    companion object {
        @JvmStatic
        @TempDir
        lateinit var tempDir: Path

        lateinit var tempFile: File

        @JvmStatic
        @BeforeAll
        fun setUp() {
            tempFile = Files.createFile(tempDir.resolve("testing-${Instant.now().toEpochMilli()}.csv")).toFile()

            tempFile.writeText(
                """time,value
                    2022-11-11T00:00:00.000Z,0
                    2022-11-11T01:00:00.000Z,60
                    2022-11-11T02:00:00.000Z,120
                """.trimIndent()
            )
        }
    }

    private lateinit var csvFileFetcher: CsvFileFetcher

    @BeforeEach
    internal fun setUp() {
        csvFileFetcher = CsvFileFetcher()
    }

    @Test
    fun `getData will return some data`() {
        val result = csvFileFetcher.getUsageData(tempFile)

        assertThat(result).isNotEmpty
    }

    @Test
    fun `getData will interpolate between missing timestamps to make the data granular to one minute`() {
        val result = csvFileFetcher.getUsageData(tempFile)

        assertThat(result).hasSize(2 * 60 + 1)  // 2 hours of data plus one record
    }

    @Test
    fun `getData will interpolate correctly`() {
        val result = csvFileFetcher.getUsageData(tempFile)
        val startTime = Instant.parse("2022-11-11T00:00:00.000Z")

        AutoCloseableSoftAssertions().use { softly ->
            (0..120).forEach {
                val expected = Record(startTime.plus(Duration.ofMinutes(it.toLong())), it.toDouble())

                softly.assertThat(result[it].time).isEqualTo(expected.time)
                softly.assertThat(result[it].value).isCloseTo(expected.value, Offset.offset(0.001))
            }
        }
    }
}