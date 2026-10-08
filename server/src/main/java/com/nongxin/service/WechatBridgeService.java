package com.nongxin.service;

/** In-memory local connection credentials; this is not a WeChat website login. */
public interface WechatBridgeService {
    enum State {
        DISCONNECTED,
        GENERATING,
        WAITING,
        SCANNED,
        CONNECTED,
        EXPIRED,
        ERROR
    }

    record ModelSettings(String provider, String model, String baseUrl, String apiKey) {}

    /** Internal identity snapshot. Only the opaque bindingId is presented to the local UI. */
    record Recipient(
            String botId, String peerId, long generation, String bindingId, boolean contextReady) {}

    enum Delivery {
        ACCEPTED,
        WAITING,
        REJECTED,
        UNKNOWN,
        SKIPPED
    }

    Recipient recipient();

    Delivery notify(Recipient expected, String text);

    /** Prepare reminder text after entering the shared send channel; null cancels that part. */
    Delivery notify(Recipient expected, java.util.function.Supplier<String> prepareText);

    record Status(
            State state,
            String qrContent,
            String detail,
            String connectedAt,
            String provider,
            String model) {}

    Status connect(ModelSettings requested);

    Status refresh();

    Status status();

    Status disconnect();

    void shutdown();
}
