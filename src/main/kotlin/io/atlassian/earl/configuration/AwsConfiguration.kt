package io.atlassian.earl.configuration

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.regions.Regions
import com.amazonaws.services.cloudwatch.AmazonCloudWatch
import com.amazonaws.services.cloudwatch.AmazonCloudWatchClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AwsConfiguration {

    @Bean
    fun credentials() = DefaultAWSCredentialsProviderChain()

    @Bean
    fun operatingRegions() = listOf(
        Regions.US_EAST_1,
        Regions.US_WEST_1,
        Regions.US_WEST_2,
        Regions.EU_WEST_1,
        Regions.EU_CENTRAL_1,
        Regions.AP_SOUTHEAST_1,
        Regions.AP_SOUTHEAST_2
    )

    @Bean
    fun cloudWatchClient(
        credentials: AWSCredentialsProvider,
        operatingRegions: List<Regions>
    ) = operatingRegions.associateWith { r ->
        AmazonCloudWatchClient.builder()
            .withCredentials(credentials)
            .withRegion(r)
            .build()
    }
}
