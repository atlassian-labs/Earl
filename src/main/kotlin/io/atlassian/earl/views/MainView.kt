package io.atlassian.earl.views

import com.amazonaws.regions.Regions
import io.atlassian.earl.cloudwatch.Point
import io.atlassian.earl.controller.AutoScalingConfig
import io.atlassian.earl.controller.AutoScalingController
import io.atlassian.earl.controller.CloudWatchMetricsController
import javafx.collections.ObservableList
import javafx.geometry.Orientation
import javafx.scene.chart.LineChart
import javafx.scene.chart.NumberAxis
import javafx.scene.chart.XYChart
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.TextField
import javafx.scene.layout.Priority
import javafx.util.StringConverter
import javafx.util.converter.DateTimeStringConverter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    private lateinit var tableNameField: TextField
    private lateinit var regionField: ComboBox<Regions>
    private lateinit var metricField: ComboBox<String>

    private lateinit var minCapacityField: TextField
    private lateinit var maxCapacityField: TextField
    private lateinit var targetCapacityField: TextField
    private lateinit var scaleOutCooldownField: TextField
    private lateinit var scaleInCooldownField: TextField

    private val calculateButtons = mutableListOf<Button>()

    override val root = vbox {

        prefHeight = 800.0
        prefWidth = 1500.0

        form {
            hbox(5) {
                spacing = 10.0

                fieldset("DynamoDb Table Details") {
                    field("Table Name") {
                        tableNameField = textfield()
                    }

                    field("Region") {
                        regionField = combobox(values = operatingRegions) {
                            value = operatingRegions.first()
                            fitToParentWidth()
                        }
                    }

                    field("Metric") {
                        metricField =
                            combobox(values = listOf("ConsumedReadCapacityUnits", "ConsumedWriteCapacityUnits")) {
                                value = "ConsumedWriteCapacityUnits"
                            }
                    }

                    field("Index Name (Optional)") {
                        textfield()
                    }

                    buttonbar {
                        button("Fetch Cloudwatch Data") {
                            action {
                                javaFxScope.launch {
                                    disableAllButtons()

                                    try {
                                        val job = processingScope.launch {
                                            cloudWatchMetricsController.fillOutCloudWatchData(
                                                chartAxis = lineChart.xAxis as NumberAxis,
                                                metric = metricField.value,
                                                region = regionField.value,
                                                seriesData = consumedDataList,
                                                tableName = tableNameField.text
                                            )
                                        }

                                        job.join()
                                    } finally {
                                        enableAllButtons()
                                    }
                                }
                            }
                        }.apply { calculateButtons.add(this) }
                    }
                }

                separator(orientation = Orientation.VERTICAL)

                fieldset("Auto Scaling Settings") {
                    field("Min Capacity") {
                        minCapacityField = textfield()
                    }

                    field("Max Capacity") {
                        maxCapacityField = textfield()
                    }

                    field("Target Capacity (0.2-0.9)") {
                        targetCapacityField = textfield {
                            text = "0.7"
                        }
                    }

                    field("Scaling Out Cooldown (s)") {
                        scaleOutCooldownField = textfield {
                            text = "300"
                        }
                    }

                    field("Scaling In Cooldown (s)") {
                        scaleInCooldownField = textfield {
                            text = "1800"
                        }
                    }

                    buttonbar {
                        button("Estimate Auto Scaling") {
                            action {
                                javaFxScope.launch {
                                    val autoScalingConfig = AutoScalingConfig(
                                        min = minCapacityField.text.toDouble(),
                                        max = maxCapacityField.text.toDouble(),
                                        target = targetCapacityField.text.toDouble(),
                                        scaleInCooldown = Duration.ofSeconds(scaleInCooldownField.text.toLong()),
                                        scaleOutCooldown = Duration.ofSeconds(scaleOutCooldownField.text.toLong())
                                    )

                                    disableAllButtons()
                                    try {
                                        val job = processingScope.launch {
                                            autoScalingController.fillInAutoScaling(
                                                autoScalingConfig = autoScalingConfig,
                                                autoScalingDataPoints = provisionedDataList,
                                                consumedCapacityUnits = consumedDataList.map { (x, y) ->
                                                    Point(
                                                        time = Instant.ofEpochMilli(x.toLong()),
                                                        value = y.toDouble()
                                                    )
                                                }
                                            )
                                        }

                                        job.join()
                                    } finally {
                                        enableAllButtons()
                                    }
                                }
                            }
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

    private fun disableAllButtons() = calculateButtons.forEach { it.isDisable = true }

    private fun enableAllButtons() = calculateButtons.forEach { it.isDisable = false }
}
