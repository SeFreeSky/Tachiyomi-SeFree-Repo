import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Miku-Doujin-SeFree"
    versionCode = 4
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    source {
        lang = "th"
        baseUrl = "https://miku-doujin.com"
    }
}
