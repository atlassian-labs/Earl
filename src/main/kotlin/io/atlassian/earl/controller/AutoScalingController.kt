package io.atlassian.earl.controller

import io.atlassian.earl.cloudwatch.Point
import javafx.collections.ObservableList
import javafx.scene.chart.XYChart
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

@Component
class AutoScalingController {

    fun fillInAutoScaling(
        autoScalingConfig: AutoScalingConfig,
        autoScalingDataPoints: ObservableList<XYChart.Data<Number, Number>>,
        consumedCapacityUnits: List<Point>,
    ) {
        val data = calculateAutoScalingPoints(autoScalingConfig, consumedCapacityUnits)

        autoScalingDataPoints.setAll(data.map { (time, value) -> XYChart.Data(time.toEpochMilli(), value) })
    }

    private fun calculateAutoScalingPoints(
        autoScalingConfig: AutoScalingConfig,
        consumedCapacityUnits: List<Point>,
    ): List<Point> {
        var lastScaleOutTime = Instant.MIN
        val lastScaleInTimes = mutableListOf<Instant>(LocalDateTime.MIN.toInstant(ZoneOffset.UTC))

        var prevValue = autoScalingConfig.min

        val result = mutableListOf<Point>()

        consumedCapacityUnits.forEachIndexed { i, (time, _) ->
            val potentialScaleOutValue = if (i >= 2 && lastScaleOutTime <= time - autoScalingConfig.scaleOutCooldown) {
                val last2Points = consumedCapacityUnits.subList(i - 1, i + 1)

                calculateScaleOutValue(autoScalingConfig, last2Points, prevValue)
            } else {
                null
            }

            val potentialScaleInValue =
                if (i >= 15 && canScaleIn(time, lastScaleInTimes, autoScalingConfig.scaleInCooldown)) {
                    val last15Points = consumedCapacityUnits.subList(i - 14, i + 1)

                    calculateScaleInValue(autoScalingConfig, last15Points, prevValue)
                } else {
                    null
                }

            if (potentialScaleInValue != null && potentialScaleOutValue != null) {
                throw RuntimeException("WTF")
            }

            val point = when {
                potentialScaleOutValue != null -> {
                    lastScaleOutTime = time
                    Point(time, potentialScaleOutValue)
                }
                potentialScaleInValue != null -> {
                    lastScaleInTimes.add(time)
                    Point(time, potentialScaleInValue)
                }
                else -> {
                    Point(time, prevValue)
                }
            }

            prevValue = point.value
            result.add(point)
        }

        return result
    }

    private fun canScaleIn(
        currentTime: Instant,
        lastScaleInTimes: MutableList<Instant>,
        scaleInCooldown: Duration,
    ): Boolean {
        if ((lastScaleInTimes.lastOrNull() ?: Instant.MIN) > currentTime - scaleInCooldown) {
            return false
        }

        val currentDay = LocalDateTime.ofInstant(currentTime, UTC).toLocalDate().atStartOfDay()
        lastScaleInTimes.removeIf { LocalDateTime.ofInstant(it, UTC) < currentDay }

        return lastScaleInTimes.size < 4 || (lastScaleInTimes.last() + Duration.ofHours(1)) < currentTime
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

    companion object {
        private val UTC = ZoneId.of("UTC")
    }
}

data class AutoScalingConfig(
    val min: Double,
    val max: Double,
    val target: Double,
    val scaleInCooldown: Duration,
    val scaleOutCooldown: Duration,
)
