# (Arcana Drama) Narto

Author: ArcanaDrama. Standard Android Cloudstream MainAPI CS3.

Compile with JDK17, Android SDK37, Gradle9.8.1/AGP9.4.1/Kotlin2.4.21. Set ANDROID_HOME or an unshared local.properties sdk.dir. Run `bash gradlew :provider:assembleRelease`.

Package `provider-release.aar/classes.jar` using AGP9.4.1 embedded D8 9.4.24 with --release --min-api26, Android37 android.jar as --lib and the compile-api classes.jar as --classpath. Put only manifest.json and classes.dex at CS3 ZIP root. SDK, compile-api, Kotlin, OkHttp, Jsoup and JSpecify are compile-only and not bundled into the CS3; the host supplies them. The corresponding addon implementation and pinned build inputs are included.

Original Cloudstream and Arcana1.1.43(75)+ use standard getVideoInterceptor with actual HTTPS resources and client-only routing fragments. Stock Play1.2.7 uses its existing phone-only loopback compatibility path. No external proxy, database bridge, account login or automatic subtitle translation.

GPL-3.0. The compile-only Jsoup jar includes its MIT license; see THIRD-PARTY-NOTICES.md.

Existing Turkish subtitle tracks are reported with the canonical `tr` language code, understood by both Cloudstream and Play. This lets Cloudstream apply its Turkish automatic-selection preference and remember a manual language choice between episodes. The addon preserves the user's host preference and does not generate missing subtitles.
