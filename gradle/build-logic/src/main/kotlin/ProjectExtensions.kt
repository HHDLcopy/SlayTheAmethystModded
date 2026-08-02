import org.gradle.api.Project

fun Project.runCommand(cmd: String, defaultValue: String = ""): String {
    val output = providers.exec {
        val osName = System.getProperty("os.name").lowercase()
        if (osName.contains("windows")) {
            commandLine("cmd", "/c", cmd)
        } else {
            commandLine("sh", "-c", cmd)
        }
    }
    return if (output.result.get().exitValue == 0) {
        output.standardOutput.asText.get().trim()
    } else {
        defaultValue
    }
}

fun Project.readGradleProperty(name: String, defaultValue: String = ""): String =
    providers.gradleProperty(name)
        .orElse(defaultValue)
        .get()
