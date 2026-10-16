package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class UploadTransportTest {
    @Test public void wifiBypassesMobileLimitsButCellularAndMeteredVpnDoNot() {
        assertFalse(UploadTransport.mobileBudget(true,false,true));
        assertFalse(UploadTransport.mobileBudget(true,true,true));
        assertTrue(UploadTransport.mobileBudget(false,true,false));
        assertTrue(UploadTransport.mobileBudget(false,false,true));
        assertFalse(UploadTransport.mobileBudget(false,false,false));
    }
}
