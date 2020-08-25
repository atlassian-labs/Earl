package io.atlassian.earl.controller

import io.atlassian.earl.cloudwatch.Point
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min


object AutoScalingCalculator {

    fun calculateAutoScalingPoints(
        autoScalingConfig: AutoScalingConfig,
        consumedCapacityUnits: List<Point>
    ): List<Point> {
        var lastScaleOutTime = Instant.MIN
        var lastScaleInTime = Instant.MIN

        var prevValue = autoScalingConfig.min

        val result = mutableListOf<Point>()

        consumedCapacityUnits.forEachIndexed { i, (time, _) ->
            val potentialScaleOutValue = if (i >= 2 && lastScaleOutTime <= time - autoScalingConfig.scaleOutCooldown) {
                val last2Points = consumedCapacityUnits.subList(i - 1, i + 1)

                calculateScaleOutValue(autoScalingConfig, last2Points, prevValue)
            } else {
                null
            }

            val potentialScaleInValue = if (i >= 15 && lastScaleInTime <= time - autoScalingConfig.scaleInCooldown) {
                val last15Points = consumedCapacityUnits.subList(i - 14, i + 1)

                calculateScaleInValue(autoScalingConfig, last15Points, prevValue)
            } else {
                null
            }

            if (potentialScaleInValue != null && potentialScaleOutValue != null) {
                throw RuntimeException("WTF")
            }

            val point = if (potentialScaleOutValue != null) {
                lastScaleOutTime = time
                Point(time, potentialScaleOutValue)
            } else if (potentialScaleInValue != null) {
                lastScaleInTime = time
                Point(time, potentialScaleInValue)
            } else {
                Point(time, prevValue)
            }

            prevValue = point.value
            result.add(point)
        }

        return result
    }

    private fun calculateScaleOutValue(
        autoScalingConfig: AutoScalingConfig,
        lastPoints: List<Point>,
        prevValue: Double
    ) = if (lastPoints.all { it.value > prevValue * autoScalingConfig.target }) {
        val desired = ceil(lastPoints.first().value / autoScalingConfig.target)
        min(desired, autoScalingConfig.max).takeIf { it != prevValue }
    } else {
        null
    }

    private fun calculateScaleInValue(
        autoScalingConfig: AutoScalingConfig,
        lastPoints: List<Point>,
        prevValue: Double
    ) = if (lastPoints.all { it.value < prevValue * (autoScalingConfig.target - 0.2) }) {
        val desired = ceil(lastPoints.first().value / autoScalingConfig.target)
        max(desired, autoScalingConfig.min).takeIf { it != prevValue }
    } else {
        null
    }
}

data class AutoScalingConfig(
    val min: Double,
    val max: Double,
    val target: Double,
    val scaleInCooldown: Duration,
    val scaleOutCooldown: Duration,
)
