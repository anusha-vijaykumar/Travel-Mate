package com.springai.travel_mate.controller;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Pattern WORD_SPLITTER = Pattern.compile("[^a-z0-9]+");
    private static final Set<String> TOOL_META_TERMS = Set.of("available", "capabilities", "capability", "help", "support",
            "supports", "tool", "tools");
    private static final Set<String> FOLLOW_UP_TERMS = Set.of("book", "confirm", "continue", "it", "next", "no", "ok",
            "okay", "same", "sure", "that", "them", "there", "these", "this", "those", "yes");

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final Set<String> allowedTerms;
    private final boolean toolsAvailable;

    public ChatController(ChatClient.Builder chatClientBuilder,
                          ChatMemory chatMemory,
                          ObjectProvider<ToolCallbackProvider> toolCallbackProvider) {
        var provider = toolCallbackProvider.getIfAvailable();
        var toolCallbacks = provider != null ? provider.getToolCallbacks() : new ToolCallback[0];

        this.toolsAvailable = toolCallbacks.length > 0;
        this.chatMemory = chatMemory;
        this.allowedTerms = extractAllowedTerms(toolCallbacks);

        if (this.toolsAvailable) {
            chatClientBuilder.defaultTools((Object[]) toolCallbacks);
        }

        this.chatClient = chatClientBuilder
                .defaultSystem(buildToolOnlySystemPrompt(toolCallbacks))
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    @GetMapping("/chat")
    public String chat(@RequestParam("message") String message,
                       @RequestParam(value = "conversationId", defaultValue = "default") String conversationId) {
        if (!toolsAvailable) {
            return "I can only help with requests supported by the travel booking tools, but those tools are unavailable.";
        }

        if (!isRelatedToMcpTools(message, conversationId)) {
            return "I can only help with requests supported by the travel booking tools.";
        }

        return chatClient.prompt(message)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
    }

    private boolean isRelatedToMcpTools(String message, String conversationId) {
        if (!StringUtils.hasText(message)) {
            return false;
        }

        var messageTerms = Arrays.stream(WORD_SPLITTER.split(message.toLowerCase(Locale.ROOT)))
                .filter(term -> term.length() >= 4)
                .collect(Collectors.toSet());

        if (messageTerms.stream().anyMatch(allowedTerms::contains)
                || messageTerms.stream().anyMatch(TOOL_META_TERMS::contains)) {
            return true;
        }

        return !chatMemory.get(conversationId).isEmpty()
                && messageTerms.stream().anyMatch(FOLLOW_UP_TERMS::contains);
    }

    private static Set<String> extractAllowedTerms(ToolCallback[] toolCallbacks) {
        var terms = new TreeSet<String>();

        for (var toolCallback : toolCallbacks) {
            var definition = toolCallback.getToolDefinition();
            addTerms(terms, definition.name());
            addTerms(terms, definition.description());
        }

        return terms;
    }

    private static void addTerms(Set<String> terms, String text) {
        if (!StringUtils.hasText(text)) {
            return;
        }

        Arrays.stream(WORD_SPLITTER.split(text.toLowerCase(Locale.ROOT)))
                .filter(term -> term.length() >= 4)
                .forEach(terms::add);
    }

    private static String buildToolOnlySystemPrompt(ToolCallback[] toolCallbacks) {
        var toolDescriptions = Arrays.stream(toolCallbacks)
                .map(toolCallback -> {
                    var definition = toolCallback.getToolDefinition();
                    return "- " + definition.name() + ": " + definition.description();
                })
                .collect(Collectors.joining("\n"));

        if (!StringUtils.hasText(toolDescriptions)) {
            toolDescriptions = "- No MCP tools are currently available.";
        }

        return """
                You are Travel Mate.

                You may only answer user requests that are directly supported by the available MCP tools.
                Use the conversation memory only to resolve context for supported tool-related requests.
                Do not answer general knowledge, coding, math, personal advice, or unrelated questions.
                If a request is not supported by the listed MCP tools, respond exactly:
                I can only help with requests supported by the travel booking tools.

                Available MCP tools:
                %s
                """.formatted(toolDescriptions);
    }
}
