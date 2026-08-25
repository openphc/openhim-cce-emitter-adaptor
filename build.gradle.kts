plugins {
    java
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
    jacoco
    id("org.sonarqube") version "6.3.1.5724"
}

group = "org.openphc.cce"
version = "1.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

val hapiFhirVersion = "7.4.0"
val wiremockVersion = "3.9.2"

dependencies {
    // Spring Boot starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.retry:spring-retry")
    implementation("org.springframework:spring-aspects")

    // HAPI FHIR
    implementation("ca.uhn.hapi.fhir:hapi-fhir-base:$hapiFhirVersion")
    implementation("ca.uhn.hapi.fhir:hapi-fhir-structures-r4:$hapiFhirVersion")

    // Observability
    implementation("io.micrometer:micrometer-registry-prometheus")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.wiremock:wiremock-standalone:$wiremockVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)   // the format Sonar ingests
        html.required.set(true)  // for local browsing
    }
}

sonar {
    properties {
        property("sonar.organization", "openphc")
        property("sonar.projectKey", "openphc_openhim-cce-emitter-adaptor")
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            "${layout.buildDirectory.get()}/reports/jacoco/test/jacocoTestReport.xml",
        )
    }
}

tasks.named("sonar") {
    dependsOn(tasks.jacocoTestReport)
}
