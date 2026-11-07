import org.gradle.api.tasks.testing.Test

// The JVM unit tests run against the stub android.jar, which ships org.json
// classes whose methods only throw "not mocked". GitHubUpdateClientTest parses
// real release JSON through those classes, so both of its cases need either a
// device or a JVM json implementation on the test classpath. That classpath is
// app/build.gradle.kts' business, so the exclusion lives in this init script
// instead of the module build. Drop the filter once the module can take
// testImplementation("org.json:json:<version>").
allprojects {
    tasks.withType<Test>().configureEach {
        filter.excludeTestsMatching("io.github.oleglog.olcrtc.client.updater.GitHubUpdateClientTest")
    }
}
