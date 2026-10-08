package com.nongxin.integration.wechat;

import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.login.LoginStatus;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Small seam around the SDK so login and message handling can be tested without WeChat. */
public interface WechatClient extends AutoCloseable {
    String executeLogin();

    CompletableFuture<LoginContext> loginFuture();

    LoginStatus.Status loginStatus();

    List<WeixinMessage> getUpdates() throws IOException;

    void sendText(String recipient, String text) throws IOException;

    boolean isLoggedIn();

    @Override
    void close();
}
