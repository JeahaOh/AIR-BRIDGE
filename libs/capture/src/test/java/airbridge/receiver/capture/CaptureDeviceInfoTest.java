package airbridge.receiver.capture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CaptureDeviceInfoTest {

    @Test
    void defaultsBlankNameToDeviceLabel() {
        CaptureDeviceInfo device = new CaptureDeviceInfo(5, "   ", true);

        assertEquals(5, device.index());
        assertEquals("Device 5", device.name());
        assertEquals(true, device.available());
        assertEquals("5 - Device 5 [available]", device.toString());
    }

    @Test
    void equalityUsesIndexNameAndAvailability() {
        CaptureDeviceInfo left = new CaptureDeviceInfo(1, "USB Camera", true);
        CaptureDeviceInfo same = new CaptureDeviceInfo(1, "USB Camera", true);
        CaptureDeviceInfo different = new CaptureDeviceInfo(1, "USB Camera", false);

        assertEquals(left, same);
        assertEquals(left.hashCode(), same.hashCode());
        assertNotEquals(left, different);
    }
}
