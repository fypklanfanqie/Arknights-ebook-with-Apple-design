plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":data:model"))
    api(project(":format:api"))
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.test {
    useJUnitPlatform()
}