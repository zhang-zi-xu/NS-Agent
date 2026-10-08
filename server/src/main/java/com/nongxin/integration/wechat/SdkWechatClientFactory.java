package com.nongxin.integration.wechat;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;

import org.springframework.stereotype.Component;

@Component
public class SdkWechatClientFactory implements WechatClientFactory {
    @Override
    public WechatClient create() {
        // The SDK heartbeat can consume updates. One explicit poller owns message intake here.
        var config =
                ILinkConfig.builder()
                        .connectTimeoutMs(35000)
                        .readTimeoutMs(35000)
                        .writeTimeoutMs(35000)
                        // Retrying a timed-out send can duplicate a notification. The application
                        // explicitly handles definite rejection and uncertain outcomes.
                        .httpMaxRetries(0)
                        .heartbeatEnabled(false)
                        .build();
        ILinkClient delegate = ILinkClient.builder().config(config).build();
        return new WechatClient() {
            public String executeLogin() {
                return delegate.executeLogin();
            }

            public java.util.concurrent.CompletableFuture<
                            com.github.wechat.ilink.sdk.core.login.LoginContext>
                    loginFuture() {
                return delegate.getLoginFuture();
            }

            public com.github.wechat.ilink.sdk.core.login.LoginStatus.Status loginStatus() {
                return delegate.getLoginStatus().getStatus();
            }

            public java.util.List<com.github.wechat.ilink.sdk.core.model.WeixinMessage> getUpdates()
                    throws java.io.IOException {
                return delegate.getUpdates();
            }

            public void sendText(String recipient, String text) throws java.io.IOException {
                delegate.sendText(recipient, text);
            }

            public boolean isLoggedIn() {
                return delegate.isLoggedIn();
            }

            public void close() {
                delegate.close();
            }
        };
    }
}
