plugins { id("com.android.library") }
android {
 namespace="dev.arcana.directdrama"
 compileSdk=37
 defaultConfig { minSdk=26 }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17); freeCompilerArgs.add("-Xstring-concat=inline") } }
dependencies {
 compileOnly("com.github.recloudstream.cloudstream:library-android:d9c55e2e5b")
 compileOnly("com.squareup.okhttp3:okhttp:5.5.0")
 compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
 compileOnly("org.jspecify:jspecify:1.0.0")
 compileOnly(files("../libs/jsoup-1.23.2-patched.jar"))
 compileOnly(project(":compile-api"))
}

dependencies {
 testImplementation("junit:junit:4.13.2")
 testImplementation("com.squareup.okhttp3:okhttp-jvm:5.5.0")
 testImplementation("org.json:json:20240303")
 testImplementation(files("../libs/jsoup-1.23.2-patched.jar"))
}
