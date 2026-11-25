package com.craxiom.networksurvey.fragments.model

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class CellularViewModelTest {
    @Test
    fun reenteringSignalPageRetainsPlmnThroughEmptyCallbacks() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val firstPage = ViewModelProvider(activity.get())["11", CellularViewModel::class.java]
            val previousPageObserver = Observer<String> {}
            firstPage.registeredPlmn.observeForever(previousPageObserver)
            firstPage.updateRegisteredPlmn("46001", false)
            shadowOf(Looper.getMainLooper()).idle()
            firstPage.registeredPlmn.removeObserver(previousPageObserver)

            firstPage.updateRegisteredPlmn("", false)
            firstPage.updateRegisteredPlmn(null, false)
            shadowOf(Looper.getMainLooper()).idle()

            val returnedPage = ViewModelProvider(activity.get())["11", CellularViewModel::class.java]
            val readings = mutableListOf<String?>()
            val observer = Observer<String> { readings.add(it) }
            returnedPage.registeredPlmn.observeForever(observer)
            assertEquals(listOf("460/01"), readings)
            returnedPage.registeredPlmn.removeObserver(observer)

            val secondSim = ViewModelProvider(activity.get())["12", CellularViewModel::class.java]
            assertNull(secondSim.registeredPlmn.value)
            secondSim.updateRegisteredPlmn("310026", false)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("310/026", secondSim.registeredPlmn.value)
            assertEquals("460/01", returnedPage.registeredPlmn.value)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun confirmedDeregistrationAndAirplaneModeClearPlmn() {
        val model = CellularViewModel()
        model.updateRegisteredPlmn("46001", false)
        model.updateRegisteredPlmn("46001", true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("", model.registeredPlmn.value)

        model.updateRegisteredPlmn("46000", false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("460/00", model.registeredPlmn.value)

        model.setAirplaneModeActive(true)
        model.updateRegisteredPlmn("46000", false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("", model.registeredPlmn.value)

        model.setAirplaneModeActive(false)
        model.updateRegisteredPlmn("46003", false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("460/03", model.registeredPlmn.value)
    }
}
