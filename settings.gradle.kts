pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Название вашего проекта в Android Studio
rootProject.name = "MyAlarmist"

// Включение модуля приложения (папки app) в сборку
include(":app")
