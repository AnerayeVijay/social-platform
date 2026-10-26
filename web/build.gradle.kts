dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation(project(":infrastructure"))
    implementation(project(":http-common"))
    implementation(platform("org.http4k:http4k-bom:6.20.0.0"))
    implementation("org.http4k:http4k-core")
    implementation("org.jsoup:jsoup:1.21.2")
    testImplementation(kotlin("test"))
    testImplementation("org.http4k:http4k-server-undertow")
    testImplementation("com.microsoft.playwright:playwright:1.56.0")
}
