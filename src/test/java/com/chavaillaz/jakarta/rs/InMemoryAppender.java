package com.chavaillaz.jakarta.rs;

import static org.apache.logging.log4j.core.layout.PatternLayout.createDefaultLayout;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;

@Plugin(name = InMemoryAppender.APPENDER_NAME,
        category = Core.CATEGORY_NAME,
        elementType = Appender.ELEMENT_TYPE)
public class InMemoryAppender extends AbstractAppender {

    public static final String APPENDER_NAME = "InMemoryAppender";

    protected final List<LogEvent> messages = new ArrayList<>();

    protected InMemoryAppender(String name, Filter filter, Layout<? extends Serializable> layout) {
        super(name, filter, layout, false, Property.EMPTY_ARRAY);
    }

    public static InMemoryAppender createDefaultAppender() {
        return createAppender(null, null, null, null);
    }

    @PluginFactory
    public static InMemoryAppender createAppender(
            @PluginAttribute("name") String name,
            @PluginElement("Filter") Filter filter,
            @PluginElement("Layout") Layout<? extends Serializable> layout,
            @PluginAttribute("otherAttribute") String otherAttribute) {
        return new InMemoryAppender(name != null ? name : APPENDER_NAME, filter, layout != null ? layout : createDefaultLayout());
    }

    @Override
    public void append(LogEvent event) {
        getMessages().add(event);
    }

    /**
     * Gets the log messages received by the appender.
     *
     * @return The list of log messages
     */
    public List<LogEvent> getMessages() {
        return this.messages;
    }

}