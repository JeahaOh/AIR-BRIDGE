package airbridge.receiver.capture;

import java.util.Objects;

public final class CaptureDeviceInfo {
    private final int index;
    private final String name;
    private final boolean available;

    public CaptureDeviceInfo(int index, String name, boolean available) {
        this.index = index;
        this.name = (name == null || name.isBlank()) ? ("Device " + index) : name;
        this.available = available;
    }

    public int index() {
        return index;
    }

    public String name() {
        return name;
    }

    public boolean available() {
        return available;
    }

    @Override
    public String toString() {
        return String.format("%d - %s [%s]", index, name, available ? "available" : "unavailable");
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CaptureDeviceInfo that)) {
            return false;
        }
        return index == that.index && available == that.available && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(index, name, available);
    }
}
