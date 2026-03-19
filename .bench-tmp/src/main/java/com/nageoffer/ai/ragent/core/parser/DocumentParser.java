package com.nageoffer.ai.ragent.core.parser;

import java.io.InputStream;
import java.util.Map;

public interface DocumentParser {
    String getParserType();
    ParseResult parse(byte[] content, String mimeType, Map<String, Object> options);
    String extractText(InputStream stream, String fileName);
    boolean supports(String mimeType);
}
