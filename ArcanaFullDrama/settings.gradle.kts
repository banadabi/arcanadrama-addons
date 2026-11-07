pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral(); maven("https://jitpack.io") } }
rootProject.name="ArcanaFullDrama"
include(":provider",":compile-api")
