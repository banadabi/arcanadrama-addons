// Each source folder retains the exact pinned Android project used by its accepted package.
val modules = listOf("ArcanaShortizle", "ArcanaNarto")
for (module in modules) {
    tasks.register<Exec>("make$module") {
        workingDir(rootDir)
        commandLine("python3", "tools/build.py", "--build", "--module", module)
    }
}
tasks.register<Exec>("make") {
    workingDir(rootDir)
    commandLine("python3", "tools/build.py", "--build", "--catalogue")
}
