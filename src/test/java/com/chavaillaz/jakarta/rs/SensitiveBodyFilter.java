package com.chavaillaz.jakarta.rs;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Example of body filter removing the secret code in JSON bodies.
 */
public class SensitiveBodyFilter implements LoggedBodyFilter {

    protected static final Pattern SECRET = Pattern.compile("\"secret-code\": \"([a-zA-Z0-9-]*)\"");

    @Override
    public void filterBody(StringBuilder body) {
        Matcher matcher = SECRET.matcher(body);
        while (matcher.find()) {
            body.replace(matcher.start(1), matcher.end(1), "masked");
        }
    }

}
