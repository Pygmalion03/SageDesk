package com.nageoffer.ai.ragent.core.parser;

public class ParseResult {
    private final String text;

    private ParseResult(String text) {
        this.text = text;
    }

    public static ParseResult ofText(String text) {
        return new ParseResult(text);
    }

    public String getText() {
        return text;
    }
}
