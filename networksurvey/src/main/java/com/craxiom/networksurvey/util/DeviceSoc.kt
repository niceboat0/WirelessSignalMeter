/*
 * Copyright 2026 The WirelessSignalMeter Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.craxiom.networksurvey.util

import android.os.Build

/**
 * The SoC identifier used in device identity, probe cache keys and lock-command arguments.
 *
 * [Build.SOC_MODEL] was added in API 31 and throws [NoSuchFieldError] on older platforms,
 * so it cannot be referenced below that level. [Build.HARDWARE] is a fine stand-in there:
 * it is a stable per-device string (e.g. "qcom", "mt6789") that serves the same purposes -
 * it only needs to be *identifying*, not pretty.
 */
object DeviceSoc {
    @JvmStatic
    val model: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL
        } else {
            Build.HARDWARE
        }

    /**
     * [Build.SOC_MANUFACTURER] is API 31+ as well. Below that, [Build.MANUFACTURER]
     * (the OEM) is the closest available attribution; diagnostics only, never matched.
     */
    @JvmStatic
    val manufacturer: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER
        } else {
            Build.MANUFACTURER
        }
}
