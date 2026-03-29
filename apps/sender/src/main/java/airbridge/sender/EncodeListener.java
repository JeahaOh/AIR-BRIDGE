package airbridge.sender;

@FunctionalInterface
interface EncodeListener {
    void onLog(String line);
}
