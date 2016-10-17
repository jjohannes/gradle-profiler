package net.rubygrapefruit.gradle.profiler

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

class ProfilerIntegrationTest extends Specification {
    @Rule
    TemporaryFolder tmpDir = new TemporaryFolder()
    ByteArrayOutputStream outputBuffer
    String gradleVersion = "3.1"
    File projectDir
    File outputDir

    String getOutput() {
        System.out.flush()
        return new String(outputBuffer.toByteArray())
    }

    LogFile getLogFile() {
        def f = new File(outputDir, "profile.log")
        assert f.isFile()
        return new LogFile(f)
    }

    File getResultFile() {
        new File(outputDir, "benchmark.csv")
    }

    File getBuildFile() {
        return new File(projectDir, "build.gradle")
    }

    File file(String path) {
        return new File(projectDir, path)
    }

    def setup() {
        Logging.resetLogging()
        outputBuffer = new ByteArrayOutputStream()
        System.out = new PrintStream(new TeeOutputStream(System.out, outputBuffer))
        projectDir = tmpDir.newFolder()
        outputDir = tmpDir.newFolder()
    }

    def cleanup() {
        Logging.resetLogging()
    }

    def "complains when no project directory provided"() {
        when:
        new Main().run()

        then:
        thrown(CommandLineParser.SettingsNotAvailableException)

        and:
        output.contains("No project directory specified.")
    }

    def "complains when neither profile or benchmark requested"() {
        when:
        new Main().run("--project-dir", projectDir.absolutePath)

        then:
        thrown(CommandLineParser.SettingsNotAvailableException)

        and:
        output.contains("Neither --profile or --benchmark specified.")
    }

    def "complains when config file contains unexpected entry"() {
        def configFile = file("benchmark.conf")
        configFile.text = """
assemble {
    gradle-version = 3.2
}
"""

        when:
        new Main().run("--project-dir", projectDir.absolutePath, "--config-file", configFile.absolutePath, "--profile")

        then:
        thrown(IllegalArgumentException)

        and:
        output.contains("Unrecognized configuration key 'assemble.gradle-version' found in configuration file.")
    }

    def "reports build failures"() {
        given:
        buildFile.text = """
apply plugin: BasePlugin
assemble.doFirst {
    throw new RuntimeException("broken!")
}
"""

        when:
        new Main().
                run("--project-dir", projectDir.absolutePath, "--output-dir", outputDir.absolutePath, "--gradle-version", gradleVersion, "--profile",
                        "assemble")

        then:
        def e = thrown(Main.ScenarioFailedException)

        and:
        logFile.contains("ERROR: failed to run build. See log file for details.")
        output.contains("ERROR: failed to run build. See log file for details.")
        logFile.contains(e.cause.message)
        output.contains(e.cause.message)
        logFile.contains("java.lang.RuntimeException: broken!")
        output.contains("java.lang.RuntimeException: broken!")
    }

    def "profiles build using specified Gradle version and tasks"() {
        given:
        buildFile.text = """
apply plugin: BasePlugin
println "<gradle-version: " + gradle.gradleVersion + ">"
println "<tasks: " + gradle.startParameter.taskNames + ">"
println "<daemon: " + gradle.services.get(org.gradle.internal.environment.GradleBuildEnvironment).longLivingProcess + ">"
"""

        when:
        new Main().
                run("--project-dir", projectDir.absolutePath, "--output-dir", outputDir.absolutePath, "--gradle-version", gradleVersion, "--profile",
                        "assemble")

        then:
        // Probe version, 2 warm up, 1 build
        logFile.grep("<gradle-version: $gradleVersion>").size() == 4
        logFile.grep("<daemon: true").size() == 4
        logFile.grep("<tasks: [assemble]>").size() == 3

        def profileFile = new File(outputDir, "profile.jfr")
        profileFile.exists()
    }

    def "runs benchmarks using tooling API for specified Gradle version and tasks"() {
        given:
        buildFile.text = """
apply plugin: BasePlugin
println "<gradle-version: " + gradle.gradleVersion + ">"
println "<tasks: " + gradle.startParameter.taskNames + ">"
println "<daemon: " + gradle.services.get(org.gradle.internal.environment.GradleBuildEnvironment).longLivingProcess + ">"
"""

        when:
        new Main().run("--project-dir", projectDir.absolutePath, "--output-dir", outputDir.absolutePath, "--gradle-version", gradleVersion,
                "--benchmark", "assemble")

        then:
        // Probe version, initial clean build, 2 warm up, 13 builds
        logFile.grep("<gradle-version: $gradleVersion>").size() == 17
        logFile.grep("<daemon: true").size() == 17
        logFile.grep("<tasks: [help]>").size() == 1
        logFile.grep("<tasks: [clean, assemble]>").size() == 1
        logFile.grep("<tasks: [assemble]>").size() == 15

        resultFile.isFile()
        resultFile.text.readLines().get(0) == "build,default 3.1"
        resultFile.text.readLines().get(1) == "tasks,assemble"
        resultFile.text.readLines().size() == 18
    }

    def "runs benchmarks using no-daemon for specified Gradle version and tasks"() {
        given:
        buildFile.text = """
apply plugin: BasePlugin
println "<gradle-version: " + gradle.gradleVersion + ">"
println "<tasks: " + gradle.startParameter.taskNames + ">"
println "<daemon: " + gradle.services.get(org.gradle.internal.environment.GradleBuildEnvironment).longLivingProcess + ">"
"""

        when:
        new Main().run("--project-dir", projectDir.absolutePath, "--output-dir", outputDir.absolutePath, "--gradle-version", gradleVersion,
                "--benchmark", "--no-daemon", "assemble")

        then:
        // Probe version, initial clean build, 2 warm up, 13 builds
        logFile.grep("<gradle-version: $gradleVersion>").size() == 17
        logFile.grep("<daemon: true").size() == 1
        logFile.grep("<daemon: false").size() == 16
        logFile.grep("<tasks: [help]>").size() == 1
        logFile.grep("<tasks: [clean, assemble]>").size() == 1
        logFile.grep("<tasks: [assemble]>").size() == 15

        resultFile.isFile()
        resultFile.text.readLines().get(0) == "build,default 3.1"
        resultFile.text.readLines().get(1) == "tasks,assemble"
        resultFile.text.readLines().size() == 18
    }

    def "runs benchmarks using scenarios defined in config file"() {
        given:
        def configFile = file("benchmark.conf")
        configFile.text = """
assemble {
    versions = ["3.0", "$gradleVersion"]
    tasks = assemble
}
help {
    versions = "$gradleVersion"
    tasks = [help]
    run-using = no-daemon
}
"""

        buildFile.text = """
apply plugin: BasePlugin
println "<gradle-version: " + gradle.gradleVersion + ">"
println "<tasks: " + gradle.startParameter.taskNames + ">"
println "<daemon: " + gradle.services.get(org.gradle.internal.environment.GradleBuildEnvironment).longLivingProcess + ">"
"""

        when:
        new Main().run("--project-dir", projectDir.absolutePath, "--output-dir", outputDir.absolutePath, "--config-file", configFile.absolutePath,
                "--benchmark")

        then:
        // Probe version, initial clean build, 2 warm up, 13 builds
        logFile.grep("<gradle-version: $gradleVersion>").size() == 1 + 16 * 2
        logFile.grep("<gradle-version: 3.0").size() == 17
        logFile.grep("<daemon: true").size() == 2 + 16 * 2
        logFile.grep("<daemon: false").size() == 16
        logFile.grep("<tasks: [help]>").size() == 2 + 15
        logFile.grep("<tasks: [clean, help]>").size() == 1
        logFile.grep("<tasks: [clean, assemble]>").size() == 2
        logFile.grep("<tasks: [assemble]>").size() == 15 * 2

        resultFile.isFile()
        resultFile.text.readLines().get(0) == "build,assemble 3.0,assemble 3.1,help 3.1"
        resultFile.text.readLines().get(1) == "tasks,assemble,assemble,help"
        resultFile.text.readLines().size() == 18
    }

    def "dry run runs test builds to verify configuration"() {
        given:
        def configFile = file("benchmark.conf")
        configFile.text = """
s1 {
    versions = ["3.0", "$gradleVersion"]
    tasks = assemble
}
s2 {
    versions = "$gradleVersion"
    tasks = [clean,assemble]
}
"""

        buildFile.text = """
apply plugin: BasePlugin
println "<gradle-version: " + gradle.gradleVersion + ">"
println "<tasks: " + gradle.startParameter.taskNames + ">"
println "<dry-run: " + gradle.startParameter.dryRun + ">"
"""

        when:
        new Main().run("--project-dir", projectDir.absolutePath, "--output-dir", outputDir.absolutePath, "--config-file", configFile.absolutePath, "--benchmark", "--dry-run")

        then:
        // Probe version, initial clean build, 1 warm up, 1 build
        logFile.grep("<gradle-version: $gradleVersion>").size() == 7
        logFile.grep("<gradle-version: 3.0").size() == 4
        logFile.grep("<dry-run: false>").size() == 2
        logFile.grep("<dry-run: true>").size() == 9
        logFile.grep("<tasks: [help]>").size() == 2
        logFile.grep("<tasks: [assemble]>").size() == 4
        logFile.grep("<tasks: [clean, assemble]>").size() == 5

        resultFile.isFile()
        resultFile.text.readLines().get(0) == "build,s1 3.0,s1 3.1,s2 3.1"
        resultFile.text.readLines().get(1) == "tasks,assemble,assemble,clean assemble"
        resultFile.text.readLines().get(2).matches("initial clean build,\\d+,\\d+,\\d+")
        resultFile.text.readLines().get(3).matches("warm-up build 1,\\d+,\\d+,\\d+")
        resultFile.text.readLines().get(4).matches("build 1,\\d+,\\d+,\\d+")
        resultFile.text.readLines().size() == 5
    }

    def "recovers from failure running benchmarks"() {
        given:
        buildFile.text = """
apply plugin: BasePlugin
println "<gradle-version: " + gradle.gradleVersion + ">"
println "<tasks: " + gradle.startParameter.taskNames + ">"

class Holder {
    static int counter
}

assemble.doFirst {
    if (gradle.gradleVersion == "${gradleVersion}" && Holder.counter++ > 3) {
        throw new RuntimeException("broken!")
    }
}
"""

        when:
        new Main().run("--project-dir", projectDir.absolutePath, "--output-dir", outputDir.absolutePath, "--gradle-version", gradleVersion,
                "--gradle-version", "3.0", "--benchmark", "assemble")

        then:
        def e = thrown(Main.ScenarioFailedException)
        logFile.contains(e.cause.message)
        output.contains(e.cause.message)
        logFile.contains("java.lang.RuntimeException: broken!")
        output.contains("java.lang.RuntimeException: broken!")

        // Probe version, initial clean build, 2 warm up, 13 builds
        logFile.grep("<gradle-version: $gradleVersion>").size() == 7
        logFile.grep("<gradle-version: 3.0>").size() == 17
        logFile.grep("<tasks: [help]>").size() == 2
        logFile.grep("<tasks: [clean, assemble]>").size() == 2
        logFile.grep("<tasks: [assemble]>").size() == 5 + 15

        resultFile.isFile()
        resultFile.text.readLines().get(0) == "build,default 3.1,default 3.0"
        resultFile.text.readLines().get(1) == "tasks,assemble,assemble"
        resultFile.text.readLines().get(2).matches("initial clean build,\\d+,\\d+")
        resultFile.text.readLines().get(3).matches("warm-up build 1,\\d+,\\d+")
        resultFile.text.readLines().get(4).matches("warm-up build 2,\\d+,\\d+")
        resultFile.text.readLines().get(5).matches("build 1,\\d+,\\d+")
        resultFile.text.readLines().get(6).matches("build 2,\\d+,\\d+")
        resultFile.text.readLines().get(7).matches("build 3,,\\d+")
        resultFile.text.readLines().size() == 18
    }

    def "can define system property when benchmarking using tooling API"() {
        given:
        buildFile.text = """
apply plugin: BasePlugin
println "<sys-prop: " + System.getProperty("org.gradle.test") + ">"
"""

        when:
        new Main().run("--project-dir", projectDir.absolutePath, "--output-dir", outputDir.absolutePath, "--gradle-version", gradleVersion,
                "--benchmark", "-Dorg.gradle.test=value", "assemble")

        then:
        // Probe version, initial clean build, 2 warm up, 13 builds
        logFile.grep("<sys-prop: null>").size() == 1
        logFile.grep("<sys-prop: value>").size() == 16
    }

    def "can define system property when benchmarking using no-daemon"() {
        given:
        buildFile.text = """
apply plugin: BasePlugin
println "<sys-prop: " + System.getProperty("org.gradle.test") + ">"
"""

        when:
        new Main().run("--project-dir", projectDir.absolutePath, "--output-dir", outputDir.absolutePath, "--gradle-version", gradleVersion,
                "--benchmark", "-Dorg.gradle.test=value", "assemble")

        then:
        // Probe version, initial clean build, 2 warm up, 13 builds
        logFile.grep("<sys-prop: null>").size() == 1
        logFile.grep("<sys-prop: value>").size() == 16
    }

    def "can define system property using config file"() {
        given:
        def configFile = file("benchmark.conf")
        configFile.text = """
a {
    versions = "$gradleVersion"
    tasks = assemble
    system-properties {
        org.gradle.test = "value-1"
    }
}
b {
    versions = "$gradleVersion"
    tasks = assemble
    system-properties {
        org.gradle.test = "value-2"
    }
}
"""
        buildFile.text = """
apply plugin: BasePlugin
println "<sys-prop: " + System.getProperty("org.gradle.test") + ">"
"""

        when:
        new Main().run("--project-dir", projectDir.absolutePath, "--output-dir", outputDir.absolutePath, "--config-file", configFile.absolutePath,
                "--benchmark")

        then:
        // Probe version, initial clean build, 2 warm up, 13 builds
        logFile.grep("<sys-prop: null>").size() == 1
        logFile.grep("<sys-prop: value-1>").size() == 16
        logFile.grep("<sys-prop: value-2>").size() == 16
    }

    def "can define Gradle args using config file"() {
        given:
        def configFile = file("benchmark.conf")
        configFile.text = """
a {
    versions = "$gradleVersion"
    tasks = assemble
    gradle-args = "-Dorg.gradle.test=value-1"
}
b {
    versions = "$gradleVersion"
    tasks = assemble
    gradle-args = ["-x", "help", "-Dorg.gradle.test=value-2"]
}
"""
        buildFile.text = """
apply plugin: BasePlugin
println "<sys-prop: " + System.getProperty("org.gradle.test") + ">"
"""

        when:
        new Main().run("--project-dir", projectDir.absolutePath, "--output-dir", outputDir.absolutePath, "--config-file", configFile.absolutePath,
                "--benchmark")

        then:
        // Probe version, initial clean build, 2 warm up, 13 builds
        logFile.grep("<sys-prop: null>").size() == 1
        logFile.grep("<sys-prop: value-1>").size() == 16
        logFile.grep("<sys-prop: value-2>").size() == 16
    }

    def "can use Gradle args to enable parallel mode"() {
        given:
        def configFile = file("benchmark.conf")
        configFile.text = """
a {
    versions = "$gradleVersion"
    tasks = assemble
    gradle-args = "--parallel"
}
"""
        buildFile.text = """
apply plugin: BasePlugin
println "<parallel: " + gradle.startParameter.parallelProjectExecutionEnabled + ">"
"""

        when:
        new Main().run("--project-dir", projectDir.absolutePath, "--output-dir", outputDir.absolutePath, "--benchmark", "--config-file",
                configFile.absolutePath)

        then:
        // Probe version, initial clean build, 2 warm up, 13 builds
        logFile.grep("<parallel: false>").size() == 1
        logFile.grep("<parallel: true>").size() == 16
        logFile.grep("Parallel execution is an incubating feature").size() == 16
    }

    static class LogFile {
        final List<String> lines

        LogFile(File logFile) {
            lines = logFile.readLines()
        }

        boolean contains(String str) {
            return grep(str).size() > 0
        }

        List<String> grep(String str) {
            return lines.findAll { it.contains(str) }
        }
    }
}