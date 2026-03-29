package airbridge.receiver;

@FunctionalInterface
interface DecodeListener {
    void onLog(String line);
}
