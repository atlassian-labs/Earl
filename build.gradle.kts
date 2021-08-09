val javaFxVersion = "11.0.2"

plugins {
    java
    id("org.springframework.boot") version "2.5.3"
    id("io.spring.dependency-management") version "1.0.10.RELEASE"
    id("org.openjfx.javafxplugin") version "0.0.9"

    kotlin("jvm") version "1.5.21"
    kotlin("plugin.spring") version "1.5.21"

    id("com.github.ben-manes.versions") version "0.36.0"
}
group = "io.atlassian"
version = "0.0.1-SNAPSHOT"

java.sourceCompatibility = JavaVersion.VERSION_11

repositories {
    mavenCentral()
}

extra["springCloudVersion"] = "Hoxton.SR7"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")

    implementation(kotlin("reflect"))
    implementation(kotlin("stdlib"))
    implementation(kotlin("stdlib-jdk7"))
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.1")

    implementation("no.tornado:tornadofx:1.7.20")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-javafx:1.5.1")

    listOf("mac", "linux", "win").forEach {
        runtimeOnly("org.openjfx:javafx-graphics:$javaFxVersion:$it")
        runtimeOnly("org.openjfx:javafx-base:$javaFxVersion:$it")
        runtimeOnly("org.openjfx:javafx-controls:$javaFxVersion:$it")
    }

    implementation("com.amazonaws:aws-java-sdk-cloudwatch")

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.7.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:3.2.0")
    testImplementation("org.assertj:assertj-core:3.20.2")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
        mavenBom("com.amazonaws:aws-java-sdk-bom:1.12.37")
    }
}

javafx {
    version = javaFxVersion
    modules = listOf("javafx.controls", "javafx.graphics")
}

tasks {
    compileKotlin {
        kotlinOptions.jvmTarget = "11"
        kotlinOptions.freeCompilerArgs = listOf("-Xjvm-default=compatibility")
    }

    compileTestKotlin {
        kotlinOptions.jvmTarget = "11"
    }

    test {
        useJUnitPlatform()
    }
}
