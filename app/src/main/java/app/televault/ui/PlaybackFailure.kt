package app.televault.ui

import androidx.media3.common.PlaybackException
import app.televault.crypto.AuthenticatedRecords

/** Only a real format/decoder-capability failure may automatically offer an external player. */
internal fun unsupportedPlayback(errorCode: Int, failure: Throwable? = null): Boolean {
    if (AuthenticatedRecords.integrityFailure(failure)) return false
    if (generateSequence(failure) { it.cause }.take(16).any { it is SecurityException }) return false
    return errorCode in setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
    )
}
