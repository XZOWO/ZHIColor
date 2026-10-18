package com.naivety.zhicolor

import com.naivety.zhicolor.home.ColorOsVersion
import org.junit.Assert.*
import org.junit.Test

class ColorOsVersionTest {
    @Test fun opPlusPropertyWins() { assertEquals("ColorOS 16.1.0", ColorOsVersion.resolve(mapOf("ro.build.version.oplusrom" to "V16.1.0", "ro.build.version.opporom" to "V15"))) }
    @Test fun oldOppoPropertyIsSupported() { assertEquals("ColorOS 15.0", ColorOsVersion.resolve(mapOf("ro.build.version.opporom" to "V15.0"))) }
    @Test fun absenceIsNotGuessedFromAndroidVersion() { assertEquals("未识别", ColorOsVersion.resolve(emptyMap())) }
    @Test fun prefixedValueIsNotDuplicated() { assertEquals("ColorOS 16.1", ColorOsVersion.resolve(mapOf("ro.build.version.oplusrom" to "ColorOS 16.1"))) }
}
