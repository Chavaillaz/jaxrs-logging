package com.chavaillaz.jakarta.rs;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Example of body filter removing the secret code in JSON bodies.
 */
public class SensitiveBodyFilter implements LoggedBodyFilter {

    protected static final Pattern SECRET = Pattern.compile("\"secret-code\": \"([a-zA-Z0-9-]*)\"");

    @Override
    public String filterBody(String body) {
        Matcher matcher = SECRET.matcher(body);
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(
                    builder,
                    matcher.group(0).replaceFirst(
                            Pattern.quote(matcher.group(1)),
                            "masked"));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }

}
