plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.protobuf) apply false
}
allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://oss.sonatype.org/content/repositories/snapshots/' }
        maven { url 'https://libgdx.badlogicgames.com/maven/' }
    }
}
