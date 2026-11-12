pluginManagement {
    // 本分支本地构建适配（2026-11-01 吞吐）：
    //   repo1.maven.org 142KB/s、dl.google.com 573KB/s
    //   maven.aliyun.com public 6.1MB/s / google 镜像 8.6MB/s
    // 镜像放最前面，原有仓库一个都不删——AGP 的插件标记、Kotlin 插件都在这些里。
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
        // NewPipeExtractor is published via JitPack.
        // 这条必须保留：com.github.TeamNewPipe:NewPipeExtractor 只有 jitpack 有，
        // 早期实现构建就是因为仓库列表里丢了 jitpack 才失败的。
        maven("https://jitpack.io")
    }
}

rootProject.name = "BitChord"
include(":shared")
include(":sharedUi")

// The Android module only when there is an SDK to build it against. A machine
// set up for the desktop alone has none, and including it there fails at
// configuration time — before the desktop module it has nothing to do with can
// build at all.
// Read as a Properties file, not line by line: Android Studio writes the path
// escaped (`C\:\Users\...` on Windows), and only Properties undoes that.
val androidSdk: String? = file("local.properties")
    .takeIf { it.isFile }
    ?.let { f -> java.util.Properties().apply { f.inputStream().use(::load) } }
    ?.getProperty("sdk.dir")
    ?.trim()
    ?: System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")

if (androidSdk != null && file(androidSdk).isDirectory) {
    include(":app")
} else {
    logger.lifecycle("No Android SDK found — building the desktop targets only.")
}
