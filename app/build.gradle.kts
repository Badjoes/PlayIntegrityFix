import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.bundling.Zip
import com.android.build.api.variant.ApplicationAndroidComponentsExtension

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "es.chiteroman.playintegrityfix"
    compileSdk = 35
    ndkVersion = "28.1.13356709"
    buildToolsVersion = "36.0.0"

    buildFeatures {
        buildConfig = true
        prefab = true
    }

    externalNativeBuild.cmake {
        path("src/CMakeLists.txt")
        buildStagingDirectory = layout.buildDirectory.get().asFile
    }

    packaging {
        jniLibs {
            excludes += "**/libdobby.so"
        }
        resources {
            excludes += "**"
        }
    }

    defaultConfig {
        applicationId = "es.chiteroman.playintegrityfix"
        minSdk = 26
        targetSdk = 35
        versionCode = 19100
        versionName = "v19.1"

        externalNativeBuild {
            cmake {
                abiFilters(
                    "arm64-v8a",
                    "armeabi-v7a"
                )
                arguments(
                    "-DANDROID_STL=none",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                )
                val commonFlags = setOf(
                    "-fno-exceptions", "-fno-rtti", "-fvisibility=hidden",
                    "-fvisibility-inlines-hidden", "-ffunction-sections",
                    "-fdata-sections", "-w"
                )
                cFlags += "-std=c23"
                cFlags += commonFlags
                cppFlags += "-std=c++26"
                cppFlags += commonFlags
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            multiDexEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
            externalNativeBuild {
                cmake {
                    arguments(
                        "-DCMAKE_BUILD_TYPE=Release",
                        "-DCMAKE_INTERPROCEDURAL_OPTIMIZATION=ON",
                        "-DCMAKE_CXX_FLAGS_RELEASE=-DNDEBUG",
                        "-DCMAKE_C_FLAGS_RELEASE=-DNDEBUG",
                    )
                }
            }
        }
        getByName("debug") {
            multiDexEnabled = false
            externalNativeBuild {
                cmake {
                    arguments("-DCMAKE_BUILD_TYPE=Debug")
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.30.5+"
        }
    }
}

dependencies {
    implementation(libs.cxx)
    implementation(libs.hiddenapibypass)
}

tasks.register("updateModuleProp") {
    doLast {
        val versionName = project.android.defaultConfig.versionName
        val versionCode = project.android.defaultConfig.versionCode
        val modulePropFile = project.rootDir.resolve("module/module.prop")
        var content = modulePropFile.readText()
        content = content.replace(Regex("version=.*"), "version=$versionName")
        content = content.replace(Regex("versionCode=.*"), "versionCode=$versionCode")
        modulePropFile.writeText(content)
    }
}

androidComponents.onVariants { variant ->
    val variantNameCapped = variant.name.replaceFirstChar { it.uppercase() }
    val zipFileName = "PlayIntegrityFix_${project.android.defaultConfig.versionName}-${variant.name}.zip"
    val zipFile = project.layout.buildDirectory.file("outputs/zips/$zipFileName").get().asFile

    val copyTask = tasks.register("copyModuleFilesFor$variantNameCapped") {
        group = "PIF Packaging"
        dependsOn("updateModuleProp", "assemble$variantNameCapped")

        doLast {
            val moduleFolder = project.rootDir.resolve("module")
            val buildDir = project.layout.buildDirectory.get().asFile
            val dexPath = if (variant.isMinifyEnabled) {
                "intermediates/dex/${variant.name}/minify${variantNameCapped}WithR8/classes.dex"
            } else {
                "intermediates/dex/${variant.name}/mergeDex${variantNameCapped}/classes.dex"
            }
            val soPath = "intermediates/stripped_native_libs/${variant.name}/strip${variantNameCapped}DebugSymbols/out/lib"
            buildDir.resolve(dexPath).copyTo(moduleFolder.resolve("classes.dex"), overwrite = true)
            buildDir.resolve(soPath).walk().filter { it.isFile && it.extension == "so" }.forEach { soFile ->
                soFile.copyTo(moduleFolder.resolve("zygisk/${soFile.parentFile.name}.so"), overwrite = true)
            }
        }
    }

    val zipTask = tasks.register<Zip>("zip$variantNameCapped") {
        group = "PIF Packaging"
        dependsOn(copyTask)
        archiveFileName.set(zipFileName)
        destinationDirectory.set(project.layout.buildDirectory.dir("outputs/zips").get().asFile)
        from(project.rootDir.resolve("module"))
    }

    val pushTask = tasks.register<Exec>("push$variantNameCapped") {
        group = "PIF Install"
        dependsOn(zipTask)
        commandLine("adb", "push", zipFile.absolutePath, "/data/local/tmp")
    }

    val installMagiskTask = tasks.register<Exec>("installMagisk$variantNameCapped") {
        group = "PIF Install"
        dependsOn(pushTask)
        commandLine("adb", "shell", "su", "-c", "magisk --install-module /data/local/tmp/$zipFileName")
    }

    val installKsuTask = tasks.register("installKsu$variantNameCapped") {
        group = "PIF Install"
        dependsOn(pushTask)
        doLast {
            exec { commandLine("adb", "shell", "su", "-c", "ksud module install /data/local/tmp/$zipFileName") }
        }
    }

    val installApatchTask = tasks.register("installApatch$variantNameCapped") {
        group = "PIF Install"
        dependsOn(pushTask)
        doLast {
            exec { commandLine("adb", "shell", "su", "-c", "apd module install /data/local/tmp/$zipFileName") }
        }
    }

    tasks.register<Exec>("installMagiskAndReboot$variantNameCapped") {
        group = "PIF Install & Reboot"
        dependsOn(installMagiskTask)
        commandLine("adb", "reboot")
    }

    tasks.register<Exec>("installKsuAndReboot$variantNameCapped") {
        group = "PIF Install & Reboot"
        dependsOn(installKsuTask)
        commandLine("adb", "reboot")
    }

    tasks.register<Exec>("installApatchAndReboot$variantNameCapped") {
        group = "PIF Install & Reboot"
        dependsOn(installApatchTask)
        commandLine("adb", "reboot")
    }
}
