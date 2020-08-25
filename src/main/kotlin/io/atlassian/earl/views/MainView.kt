package io.atlassian.earl.views

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.atlassian.earl.cloudwatch.Point
import io.atlassian.earl.controller.AutoScalingCalculator
import io.atlassian.earl.controller.AutoScalingConfig
import javafx.scene.chart.LineChart
import javafx.scene.chart.NumberAxis
import javafx.scene.layout.Priority
import javafx.scene.paint.Color
import javafx.util.StringConverter
import javafx.util.converter.DateTimeStringConverter
import tornadofx.*
import java.io.File
import java.time.Duration
import java.time.ZoneOffset
import java.util.Date

class MainView : View("My View") {
    override val root = vbox {

        prefHeight = 400.0
        prefWidth = 1500.0

        val data = jacksonObjectMapper().registerModule(JavaTimeModule()).readValue<List<Point>>(File("out.json").reader())
            .sortedBy { it.time }

        lateinit var lineChart: LineChart<Number, Number>

        button {
            text = "Fill in auto scaling"

            action {

                val autoScalingConfig = AutoScalingConfig(
                    min = 500.0,
                    max = 2900.0,
                    target = 0.65,
                    scaleInCooldown = Duration.ofSeconds(300),
                    scaleOutCooldown = Duration.ofSeconds(3600)
                )

                val provPoints = AutoScalingCalculator.calculateAutoScalingPoints(
                    autoScalingConfig = autoScalingConfig,
                    consumedCapacityUnits = data
                )


                lineChart.series("Provisioned") {
                    provPoints.forEach { (time, value) -> data(time.toEpochMilli(), value) }
                }.node.style {
                    stroke = Color.ORANGE
                }
            }
        }

        lineChart = linechart(
            title = "",
            x = NumberAxis(),
            y = NumberAxis()
        ) {
            createSymbols = false

            (xAxis as NumberAxis).apply {
                lowerBound = data.first().time.toEpochMilli().toDouble()
                upperBound = data.last().time.toEpochMilli().toDouble()
                isAutoRanging = false

                tickLabelFormatter = object : StringConverter<Number>() {
                    private val dateConverter = DateTimeStringConverter("d MMM, k:m")

                    override fun toString(n: Number) = dateConverter.toString(Date(n.toLong()))

                    override fun fromString(s: String) = dateConverter.fromString(s).time
                }

                tickLabelRotation = -90.0
                tickUnit = Duration.ofHours(12).toMillis().toDouble()
                minorTickCount = 4
            }

            vgrow = Priority.ALWAYS

            series("Consumed") {
                data.forEach { (time, value) -> data(time.toEpochMilli(), value) }

            }
        }
    }
}
