package io.atlassian.earl.csvfile

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.csv.CsvMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import org.springframework.stereotype.Component
import java.io.File
import java.time.Duration
import java.time.Instant

@Component
class CsvFileFetcher {
    private val mapper = CsvMapper()
        .registerModule(kotlinModule())
        .registerModule(JavaTimeModule())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES) as CsvMapper

    fun getUsageData(file: File): List<Record> {
        val schema = mapper.schemaFor(Record::class.java).withHeader()
        val data = mapper.readerFor(Record::class.java)
            .with(schema)
            .readValues<Record>(file)
            .asSequence()
            .toList()
            .sortedBy { it.time }

        return data.flatMapIndexed { i: Int, _: Record ->
            when {
                i < (data.size - 1) -> fillInBlanksForMinutes(data[i], data[i + 1])
                else -> listOf(data[i])
            }
        }
    }

    private fun fillInBlanksForMinutes(startRecord: Record, endRecord: Record): List<Record> {
        val interval = Duration.between(startRecord.time, endRecord.time)

        if (interval.toMinutes() <= 1) return listOf(startRecord)

        // This is super basic and assumes that this interval fits nicely in the data points
        return (startRecord.time.epochSecond until endRecord.time.epochSecond step 60).map {
            val interpolationFactor = (it - startRecord.time.epochSecond).toDouble() / interval.toSeconds()
            val interpolatedValue = startRecord.value + (endRecord.value - startRecord.value) * interpolationFactor

            Record(Instant.ofEpochSecond(it), interpolatedValue)
        }
    }
}

data class Record(
    val time: Instant,
    val value: Double
)
