package app.televault.telegram

import app.televault.domain.AuthProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class FakeGatewayTest {
    @Test fun commandsDoNotInventAuthorization() = runBlocking {
        val gateway = FakeTelegramGateway()
        gateway.submit(AuthProtocol.Action.PHONE, "test-only", "")
        assertEquals(AuthProtocol.State.PHONE, gateway.authorization.value.stage)
        gateway.submit(AuthProtocol.Action.PASSWORD, "test-only", "")
        assertEquals(1, gateway.acceptedRequests)
        gateway.serverEvent(AuthProtocol.State.READY)
        gateway.submit(AuthProtocol.Action.LOGOUT, "", "")
        assertEquals(2, gateway.acceptedRequests)
        gateway.close()
        assertEquals(AuthProtocol.State.CLOSED, gateway.authorization.value.stage)
    }
}
