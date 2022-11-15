package io.atlassian.earl.views

import com.amazonaws.regions.Regions
import io.atlassian.earl.cloudwatch.Point
import io.atlassian.earl.controller.AutoScalingConfig
import io.atlassian.earl.controller.AutoScalingController
import io.atlassian.earl.controller.CloudWatchMetricsController
import io.atlassian.earl.controller.CsvFileController
import io.atlassian.earl.controller.PricingController
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
import javafx.stage.FileChooser
import javafx.util.StringConverter
import javafx.util.converter.DateTimeStringConverter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.launch
import tornadofx.*
import java.io.File
import java.text.DecimalFormat
import java.time.Duration
import java.time.Instant
import java.util.Date
import kotlin.coroutines.EmptyCoroutineContext

class MainView : View("DynamoDb Auto Scaling Estimator") {
    private val autoScalingController: AutoScalingController by di()
    private val cloudWatchMetricsController: CloudWatchMetricsController by di()
    private val csvFileController: CsvFileController by di()
    private val operatingRegions: List<Regions> by di()
    private val pricingController: PricingController by di()

    private val javaFxScope = CoroutineScope(EmptyCoroutineContext + Dispatchers.JavaFx + SupervisorJob())
    private val processingScope = CoroutineScope(EmptyCoroutineContext + Dispatchers.Default + SupervisorJob())

    private lateinit var lineChart: LineChart<Number, Number>

    private lateinit var consumedDataList: ObservableList<XYChart.Data<Number, Number>>
    private lateinit var provisionedDataList: ObservableList<XYChart.Data<Number, Number>>

    private val metricsViewModel = MetricsViewModel(Regions.US_EAST_1)
    private val autoScalingViewModel = AutoScalingConfigViewModel()
    private val costViewModel = CostViewModel()
    private val processingProperty = SimpleBooleanProperty(false)
    private val csvFileViewModel = CsvFileViewModel()

    private val calculateButtons = mutableListOf<Button>()

    override val root = vbox {

        prefHeight = 800.0
        prefWidth = 1500.0

        form {
            hbox(5) {
                spacing = 10.0

                vbox(3) {
                    fieldset("Import DynamoDb Cloudwatch data") {
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

                        field("Index name (optional)") {
                            textfield(metricsViewModel.indexName)
                        }

                        buttonbar {
                            button("Fetch cloudwatch data") {
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

                    separator()

                    fieldset("Import from CSV") {
                        buttonbar {
                            button("Load from file") {
                                action {
                                    val result = chooseFile(
                                        title = "CSV file with usage data",
                                        filters = arrayOf(FileChooser.ExtensionFilter("Comma separated values", "*.csv")),
                                        mode = FileChooserMode.Single
                                    )

                                    if (result.isNotEmpty()) {
                                        csvFileViewModel.file.set(result.first())

                                        fetchCsvFileAction()
                                    }
                                }
                            }
                        }
                    }
                }

                separator(orientation = Orientation.VERTICAL)

                fieldset("Auto scaling settings") {
                    field("Min Capacity") {
                        textfield(autoScalingViewModel.minCapacity) {
                            validator { validateGreaterThanZero(it) }
                        }
                    }

                    field("Max capacity") {
                        textfield(autoScalingViewModel.maxCapacity) {
                            validator { validateGreaterThanZero(it) }
                        }
                    }

                    field("Target capacity (0.2-0.9)") {
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

                    field("Scaling out cooldown (s)") {
                        textfield(autoScalingViewModel.scaleOutCooldown, DurationConverter()) {
                            validator { validateGreaterThanZero(it) }
                        }
                    }

                    field("Scaling in cooldown (s)") {
                        textfield(autoScalingViewModel.scaleInCooldown, DurationConverter()) {
                            validator { validateGreaterThanZero(it) }
                        }
                    }

                    buttonbar {
                        button("Estimate auto scaling") {
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

                fieldset("Cost of capacity units") {
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

                    field("Estimated provisioned mode cost") {
                        textfield(costViewModel.totalProvisionedCost) {
                            isDisable = true
                        }
                    }

                    field("Estimated on-demand mode cost") {
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
                pricingController.calculateProvisionedCapacityCost(
                    costViewModel.provisionedPrice.value,
                    data.map { Point(Instant.ofEpochMilli(it.xValue.toLong()), it.yValue.toDouble()) }
                )
            } else {
                0.0
            }
        }
    }

    private fun calculateOnDemandCost(data: List<XYChart.Data<Number, Number>>) {
        processingScope.launch {
            costViewModel.totalOnDemandCost.value = if (costViewModel.isValid) {
                pricingController.calculateOnDemandCost(
                    costViewModel.onDemandPrice.value,
                    data.map { it.yValue.toDouble() }
                )
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

                setGraphData(data, lower, upper)
            } finally {
                processingProperty.set(false)
            }
        }
    }

    private fun fetchCsvFileAction() {
        javaFxScope.launch {
            try {
                processingProperty.set(true)

                val job = processingScope.async {
                    csvFileController.getData(csvFileViewModel.file.get())
                }

                val (data, lower, upper) = job.await()

                setGraphData(data, lower, upper)
            } finally {
                processingProperty.set(false)
            }
        }
    }

    private fun setGraphData(
        data: List<Pair<Number, Number>>,
        lower: Double,
        upper: Double
    ) {
        consumedDataList.setAll(data.map { XYChart.Data(it.first, it.second) })

        // There is a bug in the chart logic that doesn't clear the chart if you don't add a data point in there
        provisionedDataList.clear()
        provisionedDataList.add(XYChart.Data(0, 0))

        (lineChart.xAxis as NumberAxis).apply {
            lowerBound = lower
            upperBound = upper
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
    val provisionedPrice = bind { SimpleDoubleProperty(0.00013) }
    val onDemandPrice = bind { SimpleDoubleProperty(0.25) }

    val totalProvisionedCost = bind { SimpleDoubleProperty(0.0) }
    val totalOnDemandCost = bind { SimpleDoubleProperty(0.0) }
}

private class CsvFileViewModel : ViewModel() {
    val file = bind { SimpleObjectProperty<File>(null) }
}