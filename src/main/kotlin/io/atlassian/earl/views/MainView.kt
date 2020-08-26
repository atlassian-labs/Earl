package io.atlassian.earl.views

import com.amazonaws.regions.Regions
import io.atlassian.earl.cloudwatch.Point
import io.atlassian.earl.controller.AutoScalingConfig
import io.atlassian.earl.controller.AutoScalingController
import io.atlassian.earl.controller.CloudWatchMetricsController
import javafx.beans.property.SimpleDoubleProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.ObservableList
import javafx.geometry.Orientation
import javafx.scene.chart.LineChart
import javafx.scene.chart.NumberAxis
import javafx.scene.chart.XYChart
import javafx.scene.control.Button
import javafx.scene.layout.Priority
import javafx.util.StringConverter
import javafx.util.converter.DateTimeStringConverter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.launch
import tornadofx.*
import java.time.Duration
import java.time.Instant
import java.util.Date
import kotlin.coroutines.EmptyCoroutineContext

class MainView : View("DynamoDb Auto Scaling Estimator") {
    private val autoScalingController: AutoScalingController by di()
    private val cloudWatchMetricsController: CloudWatchMetricsController by di()
    private val operatingRegions: List<Regions> by di()

    private val javaFxScope = CoroutineScope(EmptyCoroutineContext + Dispatchers.JavaFx)
    private val processingScope = CoroutineScope(EmptyCoroutineContext + Dispatchers.Default)

    private lateinit var lineChart: LineChart<Number, Number>

    private lateinit var consumedDataList: ObservableList<XYChart.Data<Number, Number>>
    private lateinit var provisionedDataList: ObservableList<XYChart.Data<Number, Number>>

    private val tableNameProperty = SimpleStringProperty()
    private val regionProperty = SimpleObjectProperty(operatingRegions.first())
    private val metricProperty = SimpleStringProperty("ConsumedReadCapacityUnits")

    private val minCapacityProperty = SimpleDoubleProperty(10.0)
    private val maxCapacityProperty = SimpleDoubleProperty(100.0)
    private val targetCapacityProperty = SimpleDoubleProperty(0.7)
    private val scaleOutCooldownProperty = SimpleObjectProperty(Duration.ofSeconds(300))
    private val scaleInCooldownProperty = SimpleObjectProperty(Duration.ofSeconds(1800))

    private val calculateButtons = mutableListOf<Button>()

    override val root = vbox {

        prefHeight = 800.0
        prefWidth = 1500.0

        form {
            hbox(5) {
                spacing = 10.0

                fieldset("DynamoDb Table Details") {
                    field("Table Name") {
                        textfield(tableNameProperty)
                    }

                    field("Region") {
                        combobox(values = operatingRegions, property = regionProperty) {
                            fitToParentWidth()
                        }
                    }

                    field("Metric") {
                        combobox(
                            values = listOf("ConsumedReadCapacityUnits", "ConsumedWriteCapacityUnits"),
                            property = metricProperty
                        )
                    }

                    field("Index Name (Optional)") {
                        textfield()
                    }

                    buttonbar {
                        button("Fetch Cloudwatch Data") {
                            action { fetchCloudWatchDataAction() }
                        }.apply { calculateButtons.add(this) }
                    }
                }

                separator(orientation = Orientation.VERTICAL)

                fieldset("Auto Scaling Settings") {
                    field("Min Capacity") {
                        textfield(minCapacityProperty)
                    }

                    field("Max Capacity") {
                        textfield(maxCapacityProperty)
                    }

                    field("Target Capacity (0.2-0.9)") {
                        textfield(targetCapacityProperty)
                    }

                    field("Scaling Out Cooldown (s)") {
                        textfield(scaleOutCooldownProperty, DurationConverter())
                    }

                    field("Scaling In Cooldown (s)") {
                        textfield(scaleInCooldownProperty, DurationConverter())
                    }

                    buttonbar {
                        button("Estimate Auto Scaling") {
                            action { estimateAutoScalingAction() }
                        }.apply { calculateButtons.add(this) }
                    }
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
                consumedDataList = data
            }

            series("Provisioned") {
                provisionedDataList = data
            }
        }
    }

    private fun estimateAutoScalingAction() {
        javaFxScope.launch {
            disableAllButtons()
            try {
                val job = processingScope.async {
                    val autoScalingConfig = AutoScalingConfig(
                        min = minCapacityProperty.value,
                        max = maxCapacityProperty.value,
                        target = targetCapacityProperty.value,
                        scaleInCooldown = scaleInCooldownProperty.value,
                        scaleOutCooldown = scaleOutCooldownProperty.value
                    )

                    autoScalingController.fillInAutoScaling(
                        autoScalingConfig = autoScalingConfig,
                        consumedCapacityUnits = consumedDataList.map { (x, y) ->
                            Point(
                                time = Instant.ofEpochMilli(x.toLong()),
                                value = y.toDouble()
                            )
                        }
                    )
                }

                val data = job.await()
                provisionedDataList.setAll(data)
            } finally {
                enableAllButtons()
            }
        }
    }

    private fun fetchCloudWatchDataAction() {
        javaFxScope.launch {
            disableAllButtons()

            try {
                val job = processingScope.async {
                    cloudWatchMetricsController.fillOutCloudWatchData(
                        metric = metricProperty.value,
                        region = regionProperty.value,
                        tableName = tableNameProperty.value
                    )
                }

                val (data, lower, upper) = job.await()

                consumedDataList.setAll(data)

                (lineChart.xAxis as NumberAxis).apply {
                    lowerBound = lower
                    upperBound = upper
                }
            } finally {
                enableAllButtons()
            }
        }
    }

    private fun disableAllButtons() = calculateButtons.forEach { it.isDisable = true }

    private fun enableAllButtons() = calculateButtons.forEach { it.isDisable = false }
}

private class DurationConverter : StringConverter<Duration>() {
    override fun toString(value: Duration) = value.seconds.toString()

    override fun fromString(string: String): Duration = Duration.ofSeconds(string.toLong())
}
