// AGP 9 默认带的 KGP 版本较旧,手动对齐到 2.3.20(与 compose-compiler/serialization 一致)。
// compose/serialization 插件是独立 artifact,必须显式上 classpath,子项目才能免版本 apply。
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.20")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.3.20")
        classpath("org.jetbrains.kotlin:kotlin-serialization:2.3.20")
    }
}

plugins {
    id("com.android.application") version "9.1.0" apply false
}