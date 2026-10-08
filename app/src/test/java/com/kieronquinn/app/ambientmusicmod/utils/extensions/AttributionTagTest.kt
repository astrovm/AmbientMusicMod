package com.kieronquinn.app.ambientmusicmod.utils.extensions

import android.app.Application
import android.media.musicrecognition.IMusicRecognitionAttributionTagCallback
import android.media.musicrecognition.IMusicRecognitionService
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AttributionTagTest {
    @Test fun serviceWithoutAttributionTagReturnsNull() = runTest {
        val service = mock(IMusicRecognitionService::class.java)
        doAnswer { call ->
            call.getArgument<IMusicRecognitionAttributionTagCallback>(0).onAttributionTag(null)
            null
        }.`when`(service).getAttributionTag(any())
        assertNull(service.getAttributionTag())
    }
}
