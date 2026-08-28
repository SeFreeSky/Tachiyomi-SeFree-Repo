import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Nekopost-SeFree"
    versionCode = 9
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        lang = "th"
        baseUrl = "https://www.nekopost.net"
    }
}
