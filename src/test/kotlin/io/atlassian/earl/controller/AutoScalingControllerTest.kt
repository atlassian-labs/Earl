package io.atlassian.earl.controller

import io.atlassian.earl.controller.TestingUtils.generateCloudWatchData
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration

class AutoScalingControllerTest {
    private lateinit var controller: AutoScalingController

    private lateinit var autoScalingConfig: AutoScalingConfig

    @BeforeEach
    fun setUp() {
        controller = AutoScalingController()

        autoScalingConfig = AutoScalingConfig(
            min = 100.0,
            max = 1000.0,
            target = 0.7,
            scaleInCooldown = Duration.ofMinutes(30),
            scaleOutCooldown = Duration.ofMinutes(5),
        )
    }

    @Test
    fun `Auto scaling can predict how constant usage will work`() {
        // Flat usage of 10 for an hour
        val cwData = generateCloudWatchData(0 to 10, 60 to 10)

        val result = controller.calculateAutoScalingPoints(autoScalingConfig, cwData)

        // Will stay at the min scaling the whole time
        assertThat(result).containsExactlyElementsOf(generateCloudWatchData(0 to 100, 60 to 100))
    }

    @Nested
    inner class ScaleUpTest {
        @Test
        fun `Auto scaling will scale up when the usage cross the threshold`() {
            // Usage ramps up to above the threshold at 10 minutes
            val cwData = generateCloudWatchData(0 to 0, 10 to 100, 20 to 100)

            val result = controller.calculateAutoScalingPoints(autoScalingConfig, cwData)

            // 143 = 100 / 0.7 rounded up
            assertThat(result).containsExactlyElementsOf(generateCloudWatchData(0 to 100, 11 to 143, 20 to 143))
        }

        @Test
        fun `Auto scaling will not scale up on the boundary`() {
            // Usage ramps up to the threshold at 10 minutes
            val cwData = generateCloudWatchData(0 to 0, 10 to 70, 20 to 70)

            val result = controller.calculateAutoScalingPoints(autoScalingConfig, cwData)

            // No scale up
            assertThat(result).containsExactlyElementsOf(generateCloudWatchData(0 to 100, 20 to 100))
        }

        @Test
        fun `Auto scaling can scale up multiple times`() {
            // Usage ramps up to above the threshold at 10 minutes intervals
            val cwData = generateCloudWatchData(0 to 0, 10 to 100, 20 to 200, 30 to 300, 40 to 400, 50 to 400)

            val result = controller.calculateAutoScalingPoints(autoScalingConfig, cwData)

            // 143 = 100 / 0.7 rounded up. And so for 286, 429, 572
            assertThat(result).containsExactlyElementsOf(
                generateCloudWatchData(
                    0 to 100,
                    11 to 143,
                    21 to 286,
                    31 to 429,
                    41 to 572,
                    50 to 572
                )
            )
        }

        @Test
        fun `Auto scaling can respond in 2 minute intervals`() {
            autoScalingConfig = autoScalingConfig.copy(scaleOutCooldown = Duration.ofMinutes(2))
            val cwData = generateCloudWatchData(0 to 0, 10 to 100, 12 to 200, 14 to 300, 16 to 400, 20 to 400)

            val result = controller.calculateAutoScalingPoints(autoScalingConfig, cwData)

            // 143 = 100 / 0.7 rounded up. And so for 286, 429, 572
            assertThat(result).containsExactlyElementsOf(
                generateCloudWatchData(
                    0 to 100,
                    11 to 143,
                    13 to 286,
                    15 to 429,
                    17 to 572,
                    20 to 572
                )
            )
        }

        @Test
        fun `Auto scaling will respect the scale out cooldown`() {
            autoScalingConfig = autoScalingConfig.copy(scaleOutCooldown = Duration.ofMinutes(10))

            val cwData = generateCloudWatchData(0 to 0, 10 to 100, 15 to 200, 30 to 200)

            val result = controller.calculateAutoScalingPoints(autoScalingConfig, cwData)

            assertThat(result).containsExactlyElementsOf(
                generateCloudWatchData(
                    0 to 100,
                    11 to 143,
                    21 to 286, // Scale out can happen as early as 17 but must wait will 22 for the cool down
                    30 to 286
                )
            )
        }

        @Test
        fun `Auto scaling will not ramp up faster than 2 minutes apart despite config`() {
            autoScalingConfig = autoScalingConfig.copy(scaleOutCooldown = Duration.ZERO)

            val cwData = generateCloudWatchData(0 to 0, 10 to 100, 12 to 200, 30 to 200)

            val result = controller.calculateAutoScalingPoints(autoScalingConfig, cwData)

            assertThat(result).containsExactlyElementsOf(
                generateCloudWatchData(
                    0 to 100,
                    11 to 143,
                    13 to 286, // Scale out has to wait till at least 13 and cannot respond at 12
                    30 to 286
                )
            )
        }

        @Test
        fun `Scaling up will pick the max of the last two minutes`() {
            val cwData = generateCloudWatchData(0 to 0, 10 to 200, 11 to 100, 12 to 200, 20 to 200)

            val result = controller.calculateAutoScalingPoints(autoScalingConfig, cwData)

            assertThat(result).containsExactlyElementsOf(generateCloudWatchData(0 to 100, 11 to 286, 20 to 286))
        }
    }

    @Nested
    inner class ScaleDownTest {
        @Test
        fun `Auto scaling will scale down when usage drops below the threshold`() {
            val cwData = generateCloudWatchData(0 to 500, 10 to 300, 60 to 300)

            val result = controller.calculateAutoScalingPoints(autoScalingConfig, cwData)

            assertThat(result).containsExactlyElementsOf(
                generateCloudWatchData(
                    0 to 100,
                    1 to 715,
                    24 to 429,
                    60 to 429
                )
            )
        }

        @Test
        fun `Scale down will respect the cooldown`() {
            val cwData = generateCloudWatchData(0 to 500, 10 to 300, 15 to 200, 60 to 300)

            val result = controller.calculateAutoScalingPoints(autoScalingConfig, cwData)

            assertThat(result).containsExactlyElementsOf(
                generateCloudWatchData(
                    0 to 100,
                    1 to 715,
                    24 to 429,
                    54 to 286, // Has to wait 30 minutes
                    60 to 286
                )
            )
        }

        @Test
        fun `Scale down will happen as quick as 15 minutes`() {
            autoScalingConfig = autoScalingConfig.copy(scaleInCooldown = Duration.ofMinutes(15))

            val cwData = generateCloudWatchData(0 to 500, 10 to 300, 15 to 200, 60 to 300)

            val result = controller.calculateAutoScalingPoints(autoScalingConfig, cwData)

            assertThat(result).containsExactlyElementsOf(
                generateCloudWatchData(
                    0 to 100,
                    1 to 715,
                    24 to 429,
                    39 to 286,
                    60 to 286
                )
            )
        }

        @Test
        fun `Scale doesn't have a lower limit`() {
            autoScalingConfig = autoScalingConfig.copy(scaleInCooldown = Duration.ZERO)

            val cwData = generateCloudWatchData(0 to 500, 10 to 300, 15 to 200, 60 to 300)

            val result = controller.calculateAutoScalingPoints(autoScalingConfig, cwData)

            assertThat(result).containsExactlyElementsOf(
                generateCloudWatchData(
                    0 to 100,
                    1 to 715,
                    24 to 429,
                    29 to 286,
                    60 to 286
                )
            )
        }

        @Test
        fun `scale down will pick the max in the last 15 minutes`() {
            autoScalingConfig = autoScalingConfig.copy(scaleInCooldown = Duration.ZERO)

            val cwData = generateCloudWatchData(0 to 500, 10 to 200, 14 to 300, 15 to 200, 60 to 300)

            val result = controller.calculateAutoScalingPoints(autoScalingConfig, cwData)

            assertThat(result).containsExactlyElementsOf(
                generateCloudWatchData(
                    0 to 100,
                    1 to 715,
                    24 to 429,
                    29 to 286,
                    60 to 286
                )
            )
        }

        @Test
        fun `Scale down will only scale down up to 4 times an hour`() {
            autoScalingConfig = autoScalingConfig.copy(scaleInCooldown = Duration.ZERO)

            val cwData =
                generateCloudWatchData(0 to 1000, 10 to 499, 15 to 350, 20 to 249, 25 to 177, 30 to 125, 60 to 100)

            val result = controller.calculateAutoScalingPoints(autoScalingConfig, cwData)

            assertThat(result).containsExactlyElementsOf(
                generateCloudWatchData(
                    0 to 100,
                    1 to 1000,
                    24 to 713,
                    29 to 501,
                    34 to 356,
                    39 to 253,
                    60 to 253
                )
            )
        }

        @Test
        fun `Scale down will revert to scaling down once an hour past the first hour`() {
            autoScalingConfig = autoScalingConfig.copy(scaleInCooldown = Duration.ZERO)

            val cwData = generateCloudWatchData(
                0 to 1000,
                10 to 499,
                15 to 350,
                20 to 249,
                25 to 177,
                30 to 125,
                100 to 0,
                180 to 0
            )

            val result = controller.calculateAutoScalingPoints(autoScalingConfig, cwData)

            assertThat(result).containsExactlyElementsOf(
                generateCloudWatchData(
                    0 to 100,
                    1 to 1000,
                    24 to 713,
                    29 to 501,
                    34 to 356,
                    39 to 253,
                    100 to 179,
                    161 to 100,
                    180 to 100
                )
            )
        }

        @Test
        fun `Scale down limit will reset at midnight`() {
            autoScalingConfig = autoScalingConfig.copy(scaleInCooldown = Duration.ZERO)

            val almostMidnight = 23 * 60 + 20

            val cwData = generateCloudWatchData(
                0 to 1000,
                almostMidnight + 10 to 499,
                almostMidnight + 15 to 350,
                almostMidnight + 20 to 249,
                almostMidnight + 25 to 177,
                almostMidnight + 30 to 125,
                almostMidnight + 35 to 89,
                almostMidnight + 40 to 63,
                almostMidnight + 180 to 0
            )

            val result = controller.calculateAutoScalingPoints(autoScalingConfig, cwData)

            assertThat(result).containsExactlyElementsOf(
                generateCloudWatchData(
                    0 to 100,
                    1 to 1000,
                    almostMidnight + 24 to 713,
                    almostMidnight + 29 to 501,
                    almostMidnight + 34 to 356,
                    almostMidnight + 39 to 253,
                    almostMidnight + 44 to 179,
                    almostMidnight + 49 to 128,
                    almostMidnight + 54 to 100,
                    almostMidnight + 180 to 100
                )
            )
        }
    }
}
