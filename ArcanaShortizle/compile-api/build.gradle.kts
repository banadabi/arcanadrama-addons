plugins { id("com.android.library") }
android { namespace="dev.arcana.compileapi";compileSdk=37
 defaultConfig { minSdk=26 }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies { compileOnly("com.github.recloudstream.cloudstream:library-android:d9c55e2e5b") }
