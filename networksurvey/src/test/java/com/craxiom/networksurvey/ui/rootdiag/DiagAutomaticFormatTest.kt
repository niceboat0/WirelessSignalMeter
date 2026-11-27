package com.craxiom.networksurvey.ui.rootdiag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagAutomaticFormatTest {
    @Test
    fun needLogDoesNotAssumeQmdlWhenNoFormatWasChosen() {
        assertNull(DiagRecordingPolicy.automaticRecordingFormat(needLog = true, savedName = null))
        assertNull(DiagRecordingPolicy.automaticRecordingFormat(needLog = true, savedName = ""))
        assertNull(DiagRecordingPolicy.automaticRecordingFormat(needLog = true, savedName = "QMDL"))
        assertNull(DiagRecordingPolicy.automaticRecordingFormat(needLog = true, savedName = "qmdl"))
        assertNull(DiagRecordingPolicy.automaticRecordingFormat(needLog = false, savedName = DiagLogFormat.Qmdl.name))
        assertNull(DiagRecordingPolicy.automaticRecordingFormat(needLog = false, savedName = DiagLogFormat.Dlf.name))
    }

    @Test
    fun automaticStartUsesOnlyTheFormatChosenForThatTask() {
        assertEquals(DiagLogFormat.Qmdl, DiagRecordingPolicy.automaticRecordingFormat(true, DiagLogFormat.Qmdl.name))
        assertEquals(DiagLogFormat.Dlf, DiagRecordingPolicy.automaticRecordingFormat(true, DiagLogFormat.Dlf.name))
        assertEquals("QMDL", DiagRecordingPolicy.recordingFormatLabel(DiagLogFormat.Qmdl.name))
        assertEquals("DLF", DiagRecordingPolicy.recordingFormatLabel(DiagLogFormat.Dlf.name))
        assertNull(DiagRecordingPolicy.recordingFormatLabel("QMDL"))
    }

    @Test
    fun presenceAgreesForRecordingTaskLiveViewAndIdle() {
        assertEquals("recording", DiagRecordingPolicy.presence("task", recording = true))
        assertEquals("recording", DiagRecordingPolicy.presence("listening", recording = true))
        assertEquals("recording", DiagRecordingPolicy.presence("recording", recording = false))
        assertEquals("task", DiagRecordingPolicy.presence("task", recording = false))
        assertEquals("listening", DiagRecordingPolicy.presence("listening", recording = false))
        assertEquals("standby", DiagRecordingPolicy.presence("standby", recording = false))
        assertEquals("standby", DiagRecordingPolicy.presence("", recording = false))
        assertEquals("recording", DiagRecordingPolicy.presence(
            DiagRecordingPolicy.overlayStatus(DiagRecordingPolicy.Mode.TaskRecording, recording = true),
            recording = true,
        ))
        assertEquals("task", DiagRecordingPolicy.presence(
            DiagRecordingPolicy.overlayStatus(DiagRecordingPolicy.Mode.TaskRecording, recording = false),
            recording = false,
        ))
        assertEquals("listening", DiagRecordingPolicy.presence(
            DiagRecordingPolicy.overlayStatus(DiagRecordingPolicy.Mode.LiveView),
            recording = false,
        ))
        assertEquals("standby", DiagRecordingPolicy.presence(
            DiagRecordingPolicy.overlayStatus(DiagRecordingPolicy.Mode.Idle),
            recording = false,
        ))
    }
}
