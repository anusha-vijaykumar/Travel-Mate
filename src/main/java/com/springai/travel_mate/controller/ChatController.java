package com.springai.travel_mate.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient.Builder chatClientBuilder, ObjectProvider<ToolCallbackProvider> toolCallbackProvider) {
        toolCallbackProvider.ifAvailable(provider ->
                chatClientBuilder.defaultTools(provider.getToolCallbacks()));
        this.chatClient = chatClientBuilder.build();
    }

    @GetMapping("/chat")
    public String chat(@RequestParam("message")  String message) {
        return chatClient.prompt(message).call().content();
    }
}
