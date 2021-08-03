package io.atlassian.earl.views

import com.amazonaws.regions.Regions
import io.atlassian.earl.cloudwatch.Point
import io.atlassian.earl.controller.AutoScalingConfig
import io.atlassian.earl.controller.AutoScalingController
import io.atlassian.earl.controller.CloudWatchMetricsController
import javafx.beans.binding.Bindings
import javafx.beans.property.SimpleBooleanProperty
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.launch
import tornadofx.*
import java.text.DecimalFormat
import java.time.Duration
import java.time.Instant
import java.util.Date
import kotlin.coroutines.EmptyCoroutineContext

class MainView : View("DynamoDb Auto Scaling Estimator") {
    private val autoScalingController: AutoScalingController by di()
    private val cloudWatchMetricsController: CloudWatchMetricsController by di()
    private val operatingRegions: List<Regions> by di()

    private val javaFxScope = CoroutineScope(EmptyCoroutineContext + Dispatchers.JavaFx + SupervisorJob())
    private val processingScope = CoroutineScope(EmptyCoroutineContext + Dispatchers.Default + SupervisorJob())

    private lateinit var lineChart: LineChart<Number, Number>

    private lateinit var consumedDataList: ObservableList<XYChart.Data<Number, Number>>
    private lateinit var provisionedDataList: ObservableList<XYChart.Data<Number, Number>>

    private val metricsViewModel = MetricsViewModel(Regions.US_EAST_1)
    private val autoScalingViewModel = AutoScalingConfigViewModel()
    private val costViewModel = CostViewModel()
    private val processingProperty = SimpleBooleanProperty(false)

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
                        textfield(metricsViewModel.indexName)
                    }

                    buttonbar {
                        button("Fetch Cloudwatch Data") {
                            action { fetchCloudWatchDataAction() }
                            enableWhen(
                                Bindings.and(
                                    metricsViewModel.valid,
                                    Bindings.not(processingProperty)
                                )
                            )
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
                            enableWhen(
                                Bindings.and(
                                    autoScalingViewModel.valid,
                                    Bindings.not(processingProperty)
                                )
                            )
                        }.apply { calculateButtons.add(this) }
                    }
                }

                separator(orientation = Orientation.VERTICAL)

                fieldset("Cost of Capacity Units") {
                    field("Price per CU per hour") {
                        textfield(costViewModel.provisionedPrice, PreciseDoubleConverter()) {
                            validator { validateGreaterThanPointZero(it) }
                        }
                    }

                    field("Price per million requests") {
                        textfield(costViewModel.onDemandPrice) {
                            validator { validateGreaterThanPointZero(it) }
                        }
                    }

                    spacer()

                    field("Estimated Provisioned Mode Cost") {
                        textfield(costViewModel.totalProvisionedCost) {
                            isDisable = true
                        }
                    }

                    field("Estimated On-Demand Mode Cost") {
                        textfield(costViewModel.totalOnDemandCost) {
                            isDisable = true
                        }
                    }

                    buttonbar {
                        button("Recalculate") {
                            action {
                                calculateOnDemandCost(consumedDataList)
                                calculateProvisionedCost(provisionedDataList)
                            }
                            enableWhen(
                                Bindings.and(
                                    costViewModel.valid,
                                    Bindings.not(processingProperty)
                                )
                            )
                        }
                    }
                }
            }
        }

        stackpane {
            vgrow = Priority.ALWAYS

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
                    data.onChange { calculateOnDemandCost(it.list) }
                }

                series("Provisioned") {
                    provisionedDataList = data
                    data.onChange { calculateProvisionedCost(it.list) }
                }
            }

            progressindicator {
                visibleWhen { processingProperty }
            }
        }
    }

    private fun calculateProvisionedCost(data: List<XYChart.Data<Number, Number>>) {
        processingScope.launch {
            costViewModel.totalProvisionedCost.value = if (costViewModel.isValid) {
                data
                    // Int division to bucket into hours
                    .groupBy { it.xValue.toLong() / Duration.ofHours(1).toMillis() }
                    .mapValues { (_, values) -> values.maxOf { it.yValue.toDouble() } }
                    .mapValues { (_, cu) -> cu * costViewModel.provisionedPrice.value }
                    .values
                    .sum()
            } else {
                0.0
            }
        }
    }

    private fun calculateOnDemandCost(data: List<XYChart.Data<Number, Number>>) {
        processingScope.launch {
            costViewModel.totalOnDemandCost.value = if (costViewModel.isValid) {
                val totalUsage = data.sumByDouble { v -> v.yValue.toDouble() * 60.0 }
                totalUsage / 1_000_000 * costViewModel.onDemandPrice.value
            } else {
                0.0
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

    private fun ValidationContext.validateGreaterThanPointZero(it: String?): ValidationMessage? {
        return if (it.isNullOrBlank() || !it.isDouble() || it.toDouble() <= 0.0) {
            error("Must be greater than 0.0")
        } else {
            null
        }
    }

    private fun estimateAutoScalingAction() {
        javaFxScope.launch {
            try {
                processingProperty.set(true)

                val job = processingScope.async<List<XYChart.Data<Number, Number>>> {
                    val autoScalingConfig = AutoScalingConfig(
                        min = autoScalingViewModel.minCapacity.value,
                        max = autoScalingViewModel.maxCapacity.value,
                        target = autoScalingViewModel.targetCapacity.value,
                        scaleInCooldown = autoScalingViewModel.scaleInCooldown.value,
                        scaleOutCooldown = autoScalingViewModel.scaleOutCooldown.value
                    )

                    autoScalingController.calculateAutoScalingPoints(
                        autoScalingConfig = autoScalingConfig,
                        consumedCapacityUnits = consumedDataList.map { (x, y) ->
                            Point(
                                time = Instant.ofEpochMilli(x.toLong()),
                                value = y.toDouble()
                            )
                        })
                        .map { (time, value) -> XYChart.Data(time.toEpochMilli(), value) }
                }

                val data = job.await()
                provisionedDataList.setAll(data)
            } finally {
                processingProperty.set(false)
            }
        }
    }

    private fun fetchCloudWatchDataAction() {
        javaFxScope.launch {
            try {
                processingProperty.set(true)

                val job = processingScope.async {
                    cloudWatchMetricsController.fillOutCloudWatchData(
                        indexName = metricsViewModel.indexName.value?.takeIf { it.isNotBlank() },
                        metric = metricsViewModel.metric.value,
                        region = metricsViewModel.region.value,
                        tableName = metricsViewModel.tableName.value
                    )
                }

                val (data, lower, upper) = job.await()

                consumedDataList.setAll(data.map { XYChart.Data(it.first, it.second) })

                // There is a bug in the chart logic that doesn't clear the chart if you don't add a data point in there
                provisionedDataList.clear()
                provisionedDataList.add(XYChart.Data(0, 0))

                (lineChart.xAxis as NumberAxis).apply {
                    lowerBound = lower
                    upperBound = upper
                }
            } finally {
                processingProperty.set(false)
            }
        }
    }
}

private class DurationConverter : StringConverter<Duration>() {
    override fun toString(value: Duration) = value.seconds.toString()

    override fun fromString(string: String): Duration = Duration.ofSeconds(string.toLong())
}

private class PreciseDoubleConverter : StringConverter<Number>() {
    override fun toString(value: Number): String = DecimalFormat("0.000000").format(value)

    override fun fromString(string: String) = string.toDouble()
}

private class MetricsViewModel(defaultRegion: Regions) : ViewModel() {
    val tableName = bind { SimpleStringProperty() }
    val region = bind { SimpleObjectProperty(defaultRegion) }
    val metric = bind { SimpleStringProperty("ConsumedWriteCapacityUnits") }
    val indexName = bind { SimpleStringProperty() }
}

private class AutoScalingConfigViewModel : ViewModel() {
    val minCapacity = bind { SimpleDoubleProperty(10.0) }
    val maxCapacity = bind { SimpleDoubleProperty(100.0) }
    val targetCapacity = bind { SimpleDoubleProperty(0.7) }
    val scaleOutCooldown = bind { SimpleObjectProperty(Duration.ofSeconds(300)) }
    val scaleInCooldown = bind { SimpleObjectProperty(Duration.ofSeconds(1800)) }
}

private class CostViewModel : ViewModel() {
    val provisionedPrice = bind { SimpleDoubleProperty(0.00065) }
    val onDemandPrice = bind { SimpleDoubleProperty(0.25) }

    val totalProvisionedCost = bind { SimpleDoubleProperty(0.0) }
    val totalOnDemandCost = bind { SimpleDoubleProperty(0.0) }
}
