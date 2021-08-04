package io.atlassian.earl.controller

import io.atlassian.earl.cloudwatch.Point
import java.time.Instant

object TestingUtils {
    /**
     * This method will generate a list of [Point] objects to emulate the data that was passed as the input.
     * It will pick an arbitrary date at UTC midnight and then build data points from there.
     *
     * This method fill in the gaps so that every minute has a data point. Assumes the input is in ascending order
     * of time and has no repeat times.
     *
     * @param inputData A list of pair of int. The first int is the number of minutes after midnight. The second int
     *   is th number of CUs used
     */
    fun generateCloudWatchData(
        vararg inputData: Pair<Int, Int>
    ): List<Point> {
        val firstTime = inputData.first().first
        val lastTime = inputData.last().first

        val inputDataAsMap = inputData.associate { it }
        var lastValue = inputData.first().second

        val startTime = Instant.ofEpochSecond(1629417600) // 2021 Aug 20 00:00:00 UTC

        return (firstTime..lastTime)
            .map { time ->
                lastValue = inputDataAsMap[time] ?: lastValue
                time to lastValue
            }
            .map { (minutes, value) -> Point(startTime.plusSeconds(60L * minutes), value.toDouble()) }
    }
}
