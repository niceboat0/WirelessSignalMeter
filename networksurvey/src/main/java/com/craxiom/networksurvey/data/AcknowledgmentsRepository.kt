package com.craxiom.networksurvey.data

import com.craxiom.networksurvey.model.LibraryAcknowledgment
import com.craxiom.networksurvey.model.LibraryCategory

/**
 * Repository providing upstream, reference project, and dependency acknowledgments for WirelessSignalMeter.
 */
class AcknowledgmentsRepository {

    /**
     * Returns the complete list of library acknowledgments grouped by category.
     */
    fun getLibraryAcknowledgments(): List<LibraryAcknowledgment> {
        return buildList {
            // Special Acknowledgments
            add(
                LibraryAcknowledgment(
                    name = "Network Survey",
                    author = "Christian Rowlands and contributors",
                    description = "WirelessSignalMeter is derived from christianrowlands/android-network-survey. The upstream project provides the foundation for Android cellular measurements and survey data collection.",
                    sourceUrl = "https://github.com/christianrowlands/android-network-survey",
                    licenseName = "Apache License 2.0",
                    licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
                    category = LibraryCategory.SPECIAL_ACKNOWLEDGMENTS
                )
            )
            add(
                LibraryAcknowledgment(
                    name = "GPSTest",
                    author = "Sean J. Barbeau",
                    description = "GNSS UI components and satellite visualization code were adapted from the GPSTest open source Android app. This includes the sky view, signal strength charts, and satellite status displays.",
                    sourceUrl = "https://github.com/barbeau/gpstest",
                    licenseName = "Apache License 2.0",
                    licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
                    category = LibraryCategory.SPECIAL_ACKNOWLEDGMENTS
                )
            )
            add(
                LibraryAcknowledgment(
                    name = "Tower Collector",
                    author = "Tower Collector contributors",
                    description = "Related open source reference for cellular data collection.",
                    sourceUrl = "https://github.com/zamojski/TowerCollector",
                    licenseName = "Mozilla Public License 2.0",
                    licenseUrl = "https://www.mozilla.org/MPL/2.0/",
                    category = LibraryCategory.SPECIAL_ACKNOWLEDGMENTS
                )
            )

            // Core Libraries
            add(
                LibraryAcknowledgment(
                    name = "Dagger/Hilt",
                    author = "Google",
                    description = "Dependency injection framework for Android",
                    sourceUrl = "https://github.com/google/dagger",
                    licenseName = "Apache License 2.0",
                    licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
                    category = LibraryCategory.CORE_LIBRARIES
                )
            )
            add(
                LibraryAcknowledgment(
                    name = "Room",
                    author = "Google",
                    description = "Database abstraction layer over SQLite",
                    sourceUrl = "https://developer.android.com/jetpack/androidx/releases/room",
                    licenseName = "Apache License 2.0",
                    licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
                    category = LibraryCategory.CORE_LIBRARIES
                )
            )
            add(
                LibraryAcknowledgment(
                    name = "Retrofit",
                    author = "Square",
                    description = "Type-safe HTTP client for Android",
                    sourceUrl = "https://github.com/square/retrofit",
                    licenseName = "Apache License 2.0",
                    licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
                    category = LibraryCategory.CORE_LIBRARIES
                )
            )
            add(
                LibraryAcknowledgment(
                    name = "OkHttp",
                    author = "Square",
                    description = "HTTP client for network requests",
                    sourceUrl = "https://github.com/square/okhttp",
                    licenseName = "Apache License 2.0",
                    licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
                    category = LibraryCategory.CORE_LIBRARIES
                )
            )
            add(
                LibraryAcknowledgment(
                    name = "Network Survey Messaging",
                    author = "Craxiom",
                    description = "Protobuf messaging library for wireless survey data",
                    sourceUrl = "https://github.com/christianrowlands/network-survey-messaging",
                    licenseName = "Apache License 2.0",
                    licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
                    category = LibraryCategory.CORE_LIBRARIES
                )
            )
            add(
                LibraryAcknowledgment(
                    name = "GeoPackage Android",
                    author = "National Geospatial-Intelligence Agency",
                    description = "GeoPackage implementation for Android",
                    sourceUrl = "https://github.com/ngageoint/geopackage-android",
                    licenseName = "MIT License",
                    licenseUrl = "https://opensource.org/licenses/MIT",
                    category = LibraryCategory.CORE_LIBRARIES
                )
            )

            // UI Libraries
            add(
                LibraryAcknowledgment(
                    name = "Jetpack Compose",
                    author = "Google",
                    description = "Modern declarative UI toolkit for Android",
                    sourceUrl = "https://developer.android.com/jetpack/compose",
                    licenseName = "Apache License 2.0",
                    licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
                    category = LibraryCategory.UI_LIBRARIES
                )
            )
            add(
                LibraryAcknowledgment(
                    name = "Material Design Components",
                    author = "Google",
                    description = "Material Design 3 components and theming",
                    sourceUrl = "https://github.com/material-components/material-components-android",
                    licenseName = "Apache License 2.0",
                    licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
                    category = LibraryCategory.UI_LIBRARIES
                )
            )
            add(
                LibraryAcknowledgment(
                    name = "Miuix",
                    author = "Miuix contributors",
                    description = "Compose UI components and visual effects",
                    sourceUrl = "https://github.com/miuix-kotlin-multiplatform/miuix",
                    licenseName = "Apache License 2.0",
                    licenseUrl = "https://github.com/miuix-kotlin-multiplatform/miuix/blob/v0.9.2/LICENSE",
                    category = LibraryCategory.UI_LIBRARIES
                )
            )
            add(
                LibraryAcknowledgment(
                    name = "MaterialKolor",
                    author = "Jordon de Hoog",
                    description = "Dynamic Material color scheme generation",
                    sourceUrl = "https://github.com/jordond/MaterialKolor",
                    licenseName = "MIT License",
                    licenseUrl = "https://github.com/jordond/MaterialKolor/blob/5.0.1/LICENSE",
                    category = LibraryCategory.UI_LIBRARIES
                )
            )
            add(
                LibraryAcknowledgment(
                    name = "Baidu Maps SDK",
                    author = "Baidu",
                    description = "Map rendering and coordinate services. This proprietary SDK is used under the Baidu Map Open Platform service terms.",
                    sourceUrl = "https://lbsyun.baidu.com/",
                    licenseName = "Baidu Map Open Platform Service Terms",
                    licenseUrl = "https://lbsyun.baidu.com/index.php?title=open/law",
                    category = LibraryCategory.UI_LIBRARIES
                )
            )
            add(
                LibraryAcknowledgment(
                    name = "Vico Charts",
                    author = "Patryk Goworowski and Patrick Michalik",
                    description = "Compose chart library for data visualization",
                    sourceUrl = "https://github.com/patrykandpatrick/vico",
                    licenseName = "Apache License 2.0",
                    licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
                    category = LibraryCategory.UI_LIBRARIES
                )
            )
            add(
                LibraryAcknowledgment(
                    name = "RoundedProgressBar",
                    author = "Mack Hartley",
                    description = "Customizable circular progress bars",
                    sourceUrl = "https://github.com/MackHartley/RoundedProgressBar",
                    licenseName = "Apache License 2.0",
                    licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
                    category = LibraryCategory.UI_LIBRARIES
                )
            )
            add(
                LibraryAcknowledgment(
                    name = "Android FlexboxLayout",
                    author = "Google",
                    description = "Flexible box layout for Android",
                    sourceUrl = "https://github.com/google/flexbox-layout",
                    licenseName = "Apache License 2.0",
                    licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
                    category = LibraryCategory.UI_LIBRARIES
                )
            )

            // Utility Libraries
            add(
                LibraryAcknowledgment(
                    name = "Timber",
                    author = "Jake Wharton",
                    description = "Logger with a small, extensible API",
                    sourceUrl = "https://github.com/JakeWharton/timber",
                    licenseName = "Apache License 2.0",
                    licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
                    category = LibraryCategory.UTILITY_LIBRARIES
                )
            )
            add(
                LibraryAcknowledgment(
                    name = "Apache Commons CSV",
                    author = "Apache Software Foundation",
                    description = "Library for reading and writing CSV files",
                    sourceUrl = "https://github.com/apache/commons-csv",
                    licenseName = "Apache License 2.0",
                    licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
                    category = LibraryCategory.UTILITY_LIBRARIES
                )
            )
            add(
                LibraryAcknowledgment(
                    name = "Apache Commons Net",
                    author = "Apache Software Foundation",
                    description = "Network protocol clients for test task transfers",
                    sourceUrl = "https://github.com/apache/commons-net",
                    licenseName = "Apache License 2.0",
                    licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
                    category = LibraryCategory.UTILITY_LIBRARIES
                )
            )
            add(
                LibraryAcknowledgment(
                    name = "SnakeYAML",
                    author = "SnakeYAML Contributors",
                    description = "YAML processor for Java",
                    sourceUrl = "https://bitbucket.org/snakeyaml/snakeyaml",
                    licenseName = "Apache License 2.0",
                    licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
                    category = LibraryCategory.UTILITY_LIBRARIES
                )
            )
            add(
                LibraryAcknowledgment(
                    name = "ZXing",
                    author = "ZXing Authors",
                    description = "Barcode and QR code scanning library",
                    sourceUrl = "https://github.com/zxing/zxing",
                    licenseName = "Apache License 2.0",
                    licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
                    category = LibraryCategory.UTILITY_LIBRARIES
                )
            )
            add(
                LibraryAcknowledgment(
                    name = "Protobuf",
                    author = "Google",
                    description = "Protocol buffer serialization",
                    sourceUrl = "https://github.com/protocolbuffers/protobuf",
                    licenseName = "BSD 3-Clause License",
                    licenseUrl = "https://opensource.org/licenses/BSD-3-Clause",
                    category = LibraryCategory.UTILITY_LIBRARIES
                )
            )
            add(
                LibraryAcknowledgment(
                    name = "Gson",
                    author = "Google",
                    description = "JSON serialization/deserialization library",
                    sourceUrl = "https://github.com/google/gson",
                    licenseName = "Apache License 2.0",
                    licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
                    category = LibraryCategory.UTILITY_LIBRARIES
                )
            )
            add(
                LibraryAcknowledgment(
                    name = "WorkManager",
                    author = "Google",
                    description = "Background task scheduling library",
                    sourceUrl = "https://developer.android.com/jetpack/androidx/releases/work",
                    licenseName = "Apache License 2.0",
                    licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
                    category = LibraryCategory.UTILITY_LIBRARIES
                )
            )
        }
    }
}
