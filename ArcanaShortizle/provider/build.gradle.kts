plugins { id("com.android.library") }
android {
 namespace="dev.arcana.shortizle"
 compileSdk=37
 defaultConfig { minSdk=26 }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17); freeCompilerArgs.add("-Xstring-concat=inline") } }
dependencies {
 compileOnly("com.github.recloudstream.cloudstream:library-android:d9c55e2e5b")
 compileOnly("com.squareup.okhttp3:okhttp:5.5.0")
 compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
 compileOnly(files("../libs/jsoup-1.23.2-patched.jar"))
 compileOnly(project(":compile-api"))
}
