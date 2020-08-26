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

    private val metricsViewModel = MetricsViewModel(operatingRegions.first())
    private val autoScalingViewModel = AutoScalingConfigViewModel()

    private val calculateButtons = mutableListOf<Button>()

    override val root = vbox {

        prefHeight = 800.0
        prefWidth = 1500.0

        form {
            hbox(5) {
                spacing = 10.0

                fieldset("DynamoDb Table Details") {
                    field("Table Name") {
                        textfield(metricsViewModel.tableName).required()
                    }

                    field("Region") {
                        combobox(values = operatingRegions, property = metricsViewModel.region) {
                            fitToParentWidth()
                        }
                    }

                    field("Metric") {
                        combobox(
                            values = listOf("ConsumedReadCapacityUnits", "ConsumedWriteCapacityUnits"),
                            property = metricsViewModel.metric
                        )
                    }

                    field("Index Name (Optional)") {
                        textfield()
                    }

                    buttonbar {
                        button("Fetch Cloudwatch Data") {
                            action { fetchCloudWatchDataAction() }
                            enableWhen(metricsViewModel.valid)
                        }.apply { calculateButtons.add(this) }
                    }
                }

                separator(orientation = Orientation.VERTICAL)

                fieldset("Auto Scaling Settings") {
                    field("Min Capacity") {
                        textfield(autoScalingViewModel.minCapacity) {
                            validator { validateGreaterThanZero(it) }
                        }
                    }

                    field("Max Capacity") {
                        textfield(autoScalingViewModel.maxCapacity) {
                            validator { validateGreaterThanZero(it) }
                        }
                    }

                    field("Target Capacity (0.2-0.9)") {
                        textfield(autoScalingViewModel.targetCapacity) {
                            validator {
                                if (it.isNullOrBlank() ||
                                    !it.isDouble() ||
                                    it.toDouble() > 0.9 ||
                                    it.toDouble() < 0.2
                                ) {
                                    error("Must be between 0.2 - 0.9")
                                } else {
                                    null
                                }
                            }
                        }
                    }

                    field("Scaling Out Cooldown (s)") {
                        textfield(autoScalingViewModel.scaleOutCooldown, DurationConverter()) {
                            validator { validateGreaterThanZero(it) }
                        }
                    }

                    field("Scaling In Cooldown (s)") {
                        textfield(autoScalingViewModel.scaleInCooldown, DurationConverter()) {
                            validator { validateGreaterThanZero(it) }
                        }
                    }

                    buttonbar {
                        button("Estimate Auto Scaling") {
                            action { estimateAutoScalingAction() }
                            enableWhen(autoScalingViewModel.valid)
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

    private fun ValidationContext.validateGreaterThanZero(it: String?): ValidationMessage? {
        return if (it.isNullOrBlank() || !it.isLong() || it.toLong() <= 0) {
            error("Must be greater than 0")
        } else {
            null
        }
    }

    private fun estimateAutoScalingAction() {
        javaFxScope.launch {
            try {
                val job = processingScope.async {
                    val autoScalingConfig = AutoScalingConfig(
                        min = autoScalingViewModel.minCapacity.value,
                        max = autoScalingViewModel.maxCapacity.value,
                        target = autoScalingViewModel.targetCapacity.value,
                        scaleInCooldown = autoScalingViewModel.scaleInCooldown.value,
                        scaleOutCooldown = autoScalingViewModel.scaleOutCooldown.value
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
            }
        }
    }

    private fun fetchCloudWatchDataAction() {
        javaFxScope.launch {
            try {
                val job = processingScope.async {
                    cloudWatchMetricsController.fillOutCloudWatchData(
                        metric = metricsViewModel.metric.value,
                        region = metricsViewModel.region.value,
                        tableName = metricsViewModel.tableName.value
                    )
                }

                val (data, lower, upper) = job.await()

                consumedDataList.setAll(data)

                // There is a bug in the chart logic that doesn't clear the chart if you don't add a data point in there
                provisionedDataList.clear()
                provisionedDataList.add(XYChart.Data(0, 0))

                (lineChart.xAxis as NumberAxis).apply {
                    lowerBound = lower
                    upperBound = upper
                }
            } finally {
            }
        }
    }
}

private class DurationConverter : StringConverter<Duration>() {
    override fun toString(value: Duration) = value.seconds.toString()

    override fun fromString(string: String): Duration = Duration.ofSeconds(string.toLong())
}

private class MetricsViewModel(defaultRegion: Regions) : ViewModel() {
    val tableName = bind { SimpleStringProperty() }
    val region = bind { SimpleObjectProperty(defaultRegion) }
    val metric = bind { SimpleStringProperty("ConsumedReadCapacityUnits") }
}

private class AutoScalingConfigViewModel : ViewModel() {
    val minCapacity = bind { SimpleDoubleProperty(10.0) }
    val maxCapacity = bind { SimpleDoubleProperty(100.0) }
    val targetCapacity = bind { SimpleDoubleProperty(0.7) }
    val scaleOutCooldown = bind { SimpleObjectProperty(Duration.ofSeconds(300)) }
    val scaleInCooldown = bind { SimpleObjectProperty(Duration.ofSeconds(1800)) }
}
