package io.atlassian.earl.controller

import io.atlassian.earl.csvfile.CsvFileFetcher
import org.springframework.stereotype.Component
import java.io.File

@Component
class CsvFileController(
    private val csvFileFetcher: CsvFileFetcher
) {
    fun getData(csvFile: File) = csvFileFetcher.getUsageData(csvFile).let {
        ConsumedResourcesData(
            data = it.map { r -> r.time.toEpochMilli() to r.value },
            lower = it.minOf { x -> x.time }.toEpochMilli().toDouble(),
            upper = it.maxOf { x -> x.time }.toEpochMilli().toDouble()
        )
    }
}