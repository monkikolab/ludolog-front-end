import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// La version, de version.properties en la raiz (la misma que lee Ludolog Link). El versionCode sale
// de ella para que nunca se quede atras: 0.5.0 -> 500.
val appVersion: String = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
    .getProperty("version")
val appVersionCode: Int = appVersion.split('.').map(String::toInt).let { (a, b, c) -> a * 10000 + b * 100 + c }

// Signing material lives outside version control. Without keystore.properties the release
// build still runs, it just comes out unsigned instead of failing.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasSigning = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "com.felp.frontcomp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.felp.frontcomp"
        minSdk = 30
        targetSdk = 35
        // Sube con cada version que se instala.
        versionCode = appVersionCode
        versionName = appVersion
        // Lo que cambia la version de desarrollo (ver `dev` abajo): la carpeta de datos, si trae lo
        // de FrontComp, su nombre y que Link le habla.
        buildConfigField("String", "DATA_NAME", "\"Ludolog\"")
        buildConfigField("boolean", "IMPORT_LEGACY", "true")
        // Ludolog Dev lleva un «DEV» rojo arriba, siempre a la vista (ver DevBadge en MainActivity).
        buildConfigField("boolean", "DEV", "false")
        // El ultimo lanzamiento de monkikolab/ludolog-assets: catalogo y temas (26-09-2026). GitHub
        // redirige cada fichero a su almacen, y GameDb.get sigue la redireccion.
        buildConfigField("String", "RELEASES", "\"https://github.com/monkikolab/ludolog-assets/releases/latest/download\"")
        manifestPlaceholders["appLabel"] = "Ludolog"
        // El Link de esta Ludolog: el oficial, o Link Dev en la version de desarrollo.
        buildConfigField("String", "LINK_PACKAGE", "\"com.felp.ludologlink\"")
        manifestPlaceholders["linkPackage"] = "com.felp.ludologlink"
    }

    if (hasSigning) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 quita lo que no se usa de Compose, Media3 y Coil, y optimiza el resto: sin el, el
            // codigo eran 32 MB y Android tardaba en cargarlo y compilarlo, y la lista se trababa
            // hasta que el sistema compilaba la app (09-10-2026). Sin ofuscar: ver proguard-rules.pro.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
        // Ludolog Dev, para probar al lado de la oficial firmada (07-10-2026; reemplaza a la copia
        // `fresh`): otro paquete, su propia carpeta de datos (LudologDev), sin traer lo de
        // FrontComp, sin hacer de app de inicio (src/dev/AndroidManifest.xml) y hablando solo con
        // Link Dev (`assembleDev` en ludolog-link/console). Su permiso LINK es el de su paquete, asi
        // que ni ella ni Link Dev oyen a las oficiales, ni al reves. Una instalacion de cero se
        // prueba borrando sus datos (pm clear) y su carpeta.
        //
        //   ./gradlew assembleDev
        create("dev") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".dev"
            matchingFallbacks += "debug"
            // No depurable, como la oficial: Android la compila igual (y con los perfiles de arranque), y
            // lo que se mida de fluidez es lo que ve el usuario. Depurable, Compose iba bastante mas lento
            // y exageraba los tirones (08-10-2026).
            isDebuggable = false
            // Y reducida con R8 como la oficial, para probar el mismo codigo que reciben los usuarios.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "DATA_NAME", "\"LudologDev\"")
            buildConfigField("boolean", "IMPORT_LEGACY", "false")
            buildConfigField("String", "LINK_PACKAGE", "\"com.felp.ludologlink.dev\"")
            buildConfigField("boolean", "DEV", "true")
            manifestPlaceholders["appLabel"] = "Ludolog Dev"
            manifestPlaceholders["linkPackage"] = "com.felp.ludologlink.dev"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // The detection engine is pure Kotlin so it can be tested on the JVM, but Log calls
    // in the paths it touches would otherwise throw "not mocked".
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.13.0")
    // FileProvider, for handing ROMs to emulators as content:// URIs.
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    // Box art comes off local storage as files; Coil handles the decoding, downsampling
    // and memory cache that a grid of hundreds of covers needs.
    implementation("io.coil-kt.coil3:coil-compose:3.1.0")
    // El video del salon. Con MediaPlayer no habia forma de decirle que NO abriera
    // la pista de sonido -su deselectTrack solo admite subtitulos-, y esa pista,
    // aunque estuviera en silencio, bastaba para que el aparato bajase la salida de
    // todo lo demas. Aqui se puede apagar el audio entero, y entonces el fichero
    // puede conservar su sonido dentro sin molestar a nadie.
    implementation("androidx.media3:media3-exoplayer:1.9.0")
    // Los efectos de video en la GPU, dentro del reproductor: el tramado, el barrido y la
    // estela de fosforo del retro-futurista. Ver PhosphorFx.
    implementation("androidx.media3:media3-effect:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // Android ships org.json as stubs, so the JVM tests need a real implementation to
    // exercise catalog parsing rather than the detection engine alone.
    testImplementation("org.json:json:20250107")
}
