package com.nongxin.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.nongxin.dto.reminder.ReminderDtos;
import com.nongxin.security.CurrentUser;
import com.nongxin.service.TaskReminderService;
import com.nongxin.web.ApiExceptionHandler;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

class TaskReminderControllerTest {
    @Test
    void allRoutesAreLocalOnlyAndNeverCallBusinessForLanRequests() throws Exception {
        var service = mock(TaskReminderService.class);
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new TaskReminderController(service, new CurrentUser()))
                        .build();
        for (var builder :
                List.of(
                        get("/api/wechat/reminders"),
                        post("/api/wechat/reminders/suggest").content("{\"taskId\":\"t\"}"),
                        put("/api/wechat/reminders/t").content("{}"),
                        delete("/api/wechat/reminders/t?version=1"),
                        post("/api/wechat/reminders/t/retry").content("{}"))) {
            mvc.perform(
                            builder.contentType(MediaType.APPLICATION_JSON)
                                    .with(
                                            request -> {
                                                request.setRemoteAddr("192.168.1.23");
                                                return request;
                                            }))
                    .andExpect(status().isForbidden())
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        mvc.perform(
                        get("/api/wechat/reminders")
                                .header("Origin", "https://unrelated.example")
                                .with(
                                        request -> {
                                            request.setRemoteAddr("127.0.0.1");
                                            return request;
                                        }))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void localListingIsNotCachedAndIdentityFailureCannotFallBack() throws Exception {
        var service = mock(TaskReminderService.class);
        var user = new CurrentUser();
        when(service.list())
                .thenReturn(
                        new ReminderDtos.Listing(
                                new ReminderDtos.Connection(false, false, null), List.of()));
        var mvc =
                MockMvcBuilders.standaloneSetup(new TaskReminderController(service, user))
                        .setControllerAdvice(new ApiExceptionHandler())
                        .build();
        mvc.perform(
                        get("/api/wechat/reminders")
                                .with(
                                        request -> {
                                            request.setRemoteAddr("127.0.0.1");
                                            return request;
                                        }))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        clearInvocations(service);
        user.setResolver(() -> null);
        mvc.perform(
                        get("/api/wechat/reminders")
                                .with(
                                        request -> {
                                            request.setRemoteAddr("127.0.0.1");
                                            return request;
                                        }))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(service);
    }
}
