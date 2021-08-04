package io.atlassian.earl.controller

import io.atlassian.earl.controller.TestingUtils.generateCloudWatchData
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration

class PricingControllerTest {
    private lateinit var controller: PricingController

    @BeforeEach
    fun setUp() {
        controller = PricingController()
    }

    @Nested
    inner class ProvisionedCapacityPricingTest {

        private val cuPrice = 3.0

        @Test
        fun `Provision cost is calculated correctly`() {
            val data = generateCloudWatchData(0 to 1, 59 to 1)

            assertThat(controller.calculateProvisionedCapacityCost(cuPrice, data)).isEqualTo(cuPrice)
        }

        @Test
        fun `Provisioned cost is calculated for multiple hours`() {
            val data = generateCloudWatchData(0 to 1, Duration.ofDays(2).minusMinutes(1).toMinutes().toInt() to 1)

            assertThat(controller.calculateProvisionedCapacityCost(cuPrice, data)).isEqualTo(cuPrice * 48)
        }

        @Test
        fun `Provisioned cost calculations account for varying usage`() {
            val data = generateCloudWatchData(0 to 1, 60 to 2, 120 to 3, 179 to 3)

            assertThat(controller.calculateProvisionedCapacityCost(cuPrice, data)).isEqualTo(cuPrice * 6)
        }

        @Test
        fun `Provisioned cost treats incomplete hours as the whole hour`() {
            val data = generateCloudWatchData(30 to 1, 89 to 1)

            assertThat(controller.calculateProvisionedCapacityCost(cuPrice, data)).isEqualTo(cuPrice * 2)
        }

        @Test
        fun `Provisioned cost takes the max data point of the hour`() {
            val data = generateCloudWatchData(0 to 1, 30 to 2, 31 to 1, 59 to 1)

            assertThat(controller.calculateProvisionedCapacityCost(cuPrice, data)).isEqualTo(cuPrice * 2)
        }
    }

    @Nested
    inner class OnDemandPricingTest {
        private val odPricePerMil = 5.0

        @Test
        fun `On demand pricing calculates the cost per million`() {
            val data = (1..5).map { 1_000_000 / 5.0 / 60.0 }

            assertThat(controller.calculateOnDemandCost(odPricePerMil, data)).isCloseTo(
                odPricePerMil,
                Offset.offset(0.01)
            )
        }
    }
}
