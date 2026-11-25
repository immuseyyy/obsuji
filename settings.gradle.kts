// NOTE: repositories are a local file-based Maven repo populated by
// ~/workspace/android-tools/fetch-artifact.py (curl/Python downloads), because
// this sandbox resets Java's TCP connections (even to localhost), so Gradle
// cannot use any HTTP repository. See ~/workspace/android-tools/README note in
// the parent agent report.
pluginManagement {
    repositories {
        maven {
            url = uri("file:///home/hatch/workspace/android-tools/local-maven/")
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri("file:///home/hatch/workspace/android-tools/local-maven/")
        }
    }
}
rootProject.name = "obsidian-editor-shell"
include(":app")
