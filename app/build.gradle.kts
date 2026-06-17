plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // namespace = пакет исходников (для генерации R и BuildConfig).
    // applicationId = идентификатор приложения для Play — он МОЖЕТ отличаться.
    namespace = "com.example.connectapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cakeasaur.connectapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Не минифицируем debug — иначе долго и трудно отлаживать.
            isMinifyEnabled = false
            // Отдельный applicationId — debug ставится РЯДОМ с release-сборкой
            // (та подписана другим ключом → иначе INSTALL_FAILED_UPDATE_INCOMPATIBLE).
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        // Совместимость: Kotlin 1.9.24 → Compose Compiler 1.5.14
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // HiveMQ MQTT тащит Netty, у которого в каждом подмодуле
            // отдельный INDEX.LIST/DEPENDENCIES/io.netty.versions.properties —
            // merge ругается на дубликаты.
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // Material 1.12.0 нужна ТОЛЬКО для XML-темы (Theme.Material3.DayNight.NoActionBar).
    // AppCompat НЕ нужен — все Activity наследуются от ComponentActivity, не AppCompatActivity.
    implementation("com.google.android.material:material:1.12.0")

    // Lifecycle + ViewModel + SavedState.
    // lifecycle-runtime-compose даёт collectAsStateWithLifecycle().
    // lifecycle-viewmodel-compose НЕ нужен — все Activity создают VM через by viewModels()
    // из activity-ktx, а не через compose-helper viewModel().
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.activity:activity-compose:1.9.2")

    // Compose BOM управляет версиями всех compose-* артефактов
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // DataStore Preferences (настройки приложения)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // EncryptedSharedPreferences — пароль MQTT-брокера лежит зашифрованным
    // мастер-ключом из Android Keystore. Plain-text в DataStore был бы виден
    // при бэкапе / ADB-доступе.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Vico удалён — все line-charts мигрировали на кастомный NeonChart
    // (ui/graph/NeonChart.kt), который рисует через Canvas+Path с
    // multi-axis, envelope, sigma, glow и phase-lock. Math-section
    // использует свою реализацию FFT (math/Fft.kt), не из Vico.

    // USB serial — поддержка CP210x/CDC/FTDI/CH34x/Prolific.
    // Альтернатива HC-05/Wi-Fi: USB-OTG-кабель прямо к плате через UART-bridge.
    // Стабильнее BT, быстрее (до 921600 baud), работает без сопряжения.
    implementation("com.github.mik3y:usb-serial-for-android:3.7.3")

    // HiveMQ MQTT 5 client — MQTT-мост для интеграции с Home Assistant,
    // Grafana, Node-RED. Java-библиотека с async API (CompletableFuture),
    // оборачиваем в корутины. Поддерживает MQTT 3.1.1 fallback и
    // встроенный auto-reconnect.
    implementation("com.hivemq:hivemq-mqtt-client:1.3.5")
    // Netty уже тянется HiveMQ как runtime-зависимость, но `runtime` scope
    // не доступен на compile classpath — нам нужен InsecureTrustManagerFactory
    // для self-signed CA, поэтому объявляем netty-handler явно (та же версия).
    implementation("io.netty:netty-handler:4.1.118.Final")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.03"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
