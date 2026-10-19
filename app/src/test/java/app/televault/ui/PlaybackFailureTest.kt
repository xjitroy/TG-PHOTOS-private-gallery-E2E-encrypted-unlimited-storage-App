package app.televault.ui

import androidx.media3.common.PlaybackException
import app.televault.crypto.AuthenticatedRecords
import org.junit.Assert.*
import org.junit.Test

class PlaybackFailureTest {
    @Test fun unsupportedCodecsAndContainersOfferAnotherPlayer() {
        for (code in listOf(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)) assertTrue(unsupportedPlayback(code))
    }

    @Test fun networkStorageAndMalformedFilesKeepRetryInsteadOfAutomaticExternalLaunch() {
        for (code in listOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_IO_UNSPECIFIED, PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED)) assertFalse(unsupportedPlayback(code))
    }

    @Test fun integrityAndPermissionFailuresCannotAutomaticallyExportEvenWithWrongClassification() {
        val code = PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
        assertFalse(unsupportedPlayback(code, AuthenticatedRecords.IntegrityException("Synthetic")))
        assertFalse(unsupportedPlayback(code, IllegalStateException(SecurityException())))
    }
}
