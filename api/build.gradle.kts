dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation(project(":infrastructure"))
    implementation(project(":http-common"))
    implementation(platform("org.http4k:http4k-bom:6.20.0.0"))
    implementation("org.http4k:http4k-core")
    implementation("org.http4k:http4k-format-jackson")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.20.1")
    testImplementation(kotlin("test"))
    testImplementation("org.http4k:http4k-testing-approval")
}
