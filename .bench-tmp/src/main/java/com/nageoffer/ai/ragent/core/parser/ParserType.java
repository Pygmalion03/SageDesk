package com.nageoffer.ai.ragent.core.parser;

public enum ParserType {
    MARKDOWN("markdown");

    private final String type;

    ParserType(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }
}
