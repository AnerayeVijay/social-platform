dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation(platform("org.http4k:http4k-bom:6.20.0.0"))
    implementation("org.http4k:http4k-core")
    testImplementation(kotlin("test"))
}
