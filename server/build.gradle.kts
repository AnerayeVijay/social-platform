plugins { application }

dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation(project(":infrastructure"))
    implementation(project(":http-common"))
    implementation(project(":api"))
    implementation(project(":web"))
    implementation(platform("org.http4k:http4k-bom:6.20.0.0"))
    implementation("org.http4k:http4k-core")
    implementation("org.http4k:http4k-server-undertow")
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("org.flywaydb:flyway-core:11.14.1")
    testImplementation(kotlin("test"))
}

application { mainClass.set("com.example.social.server.MainKt") }
