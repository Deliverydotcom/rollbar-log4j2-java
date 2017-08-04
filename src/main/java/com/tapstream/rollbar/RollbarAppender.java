package com.tapstream.rollbar;

import com.google.gson.JsonObject;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.impl.ThrowableProxy;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.StructuredDataMessage;

import java.io.IOException;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;

/**
 * Log4j2 Appender for Rollbar
 */
@Plugin(name = "Rollbar", category = "Core", elementType = "appender", printObject = true)
public class RollbarAppender extends AbstractAppender {

    private static final String ENV_VAR_APIKEY = "ROLLBAR_LOGBACK_API_KEY";

    private NotifyBuilder payloadBuilder;

    private URL url;
    private String apiKey;
    private String environment;
    private IHttpRequester httpRequester = new HttpRequester();

    private RollbarAppender(String name, Layout<? extends Serializable> layout, Filter filter, String url, String apiKey, String environment)
    {
        super(name, filter, layout);
        setUrl(url);
        this.apiKey = apiKey;
        this.environment = environment;
    }

    @PluginFactory
    public static RollbarAppender createAppender(
                    @PluginAttribute("name") String name,
                    @PluginElement("Layout") Layout<? extends Serializable> layout,
                    @PluginElement("Filters") Filter filter,
                    @PluginAttribute("url") String url,
                    @PluginAttribute("apikey") String apiKey,
                    @PluginAttribute("environment") String env
                                                )
    {

        if (name == null)
        {
            LOGGER.error("No name provided for RollbarAppender");
            return null;
        } else
        {
            if (layout == null)
            {
                layout = PatternLayout.createDefaultLayout();
            }

            if (url == null)
            {
                url = "https://api.rollbar.com/api/1/item/";
            }

            if (apiKey == null || apiKey.trim().isEmpty())
            {
                LOGGER.error("Key is required in order to use Rollbar. Please get your key here https://rollbar.com");
            }

            if (env == null || env.trim().isEmpty())
            {
                LOGGER.error("Please provide the environment is required.");
            }

            return new RollbarAppender(name, layout, filter, url, apiKey, env);
        }
    }

    public void setHttpRequester(IHttpRequester httpRequester)
    {
        this.httpRequester = httpRequester;
    }

    public void setUrl(String url)
    {
        try
        {
            this.url = new URL(url);
        } catch (MalformedURLException e)
        {
            LOGGER.error("Error setting url", e);
        }
    }

    public void setApiKey(String apiKey)
    {
        this.apiKey = apiKey;
    }

    public void setEnvironment(String environment)
    {
        this.environment = environment;
    }

    @Override
    public void append(LogEvent logEvent)
    {
        String levelName = logEvent.getLevel().toString().toLowerCase();
        Message message = logEvent.getMessage();
        Map<String, String> propertyMap = ThreadContext.getContext();

        Throwable throwable = null;
        ThrowableProxy throwableProxy = logEvent.getThrownProxy();
        if (throwableProxy != null)
        {
            throwable = throwableProxy.getThrowable();
        }
        String messageBody;
        if(message instanceof StructuredDataMessage){
            messageBody = message.getFormat();
            propertyMap.putAll(((StructuredDataMessage) message).getData());
        } else {
            messageBody = message.getFormattedMessage();
        }
        final JsonObject payload = payloadBuilder.build(levelName, messageBody, throwable, propertyMap);
        final HttpRequest request = new HttpRequest(url, "POST");
        request.setHeader("Content-Type", "application/json");
        request.setHeader("Accept", "application/json");
        request.setBody(payload.toString());

        sendRequest(request);
    }

    @Override
    public void start()
    {
        boolean error = false;

        try
        {
            String environmentApiKey = System.getenv(ENV_VAR_APIKEY);
            if (environmentApiKey != null)
            {
                this.apiKey = environmentApiKey;
            }
        } catch (SecurityException e)
        {
            LOGGER.warn("Access to environment variables was denied. (" + e.getMessage() + ")");
        }

        if (this.url == null)
        {
            LOGGER.error("No url set for the appender named [" + getName() + "].");
            error = true;
        }
        if (this.apiKey == null || this.apiKey.isEmpty())
        {
            LOGGER.error("No apiKey set for the appender named [" + getName() + "].");
            error = true;
        }
        if (this.environment == null || this.environment.isEmpty())
        {
            LOGGER.error("No environment set for the appender named [" + getName() + "].");
            error = true;
        }

        payloadBuilder = new NotifyBuilder(apiKey, environment);

        if (!error)
        {
            super.start();
        }

    }

    @Override
    public void stop()
    {
        super.stop();
    }

    private void sendRequest(HttpRequest request)
    {
        try
        {
            int statusCode = httpRequester.send(request);
            if (statusCode >= 200 && statusCode <= 299)
            {
                // Everything went OK
            } else
            {
                LOGGER.error("Non-2xx response from Rollbar: " + statusCode);
            }

        } catch (IOException e)
        {
            LOGGER.error("Exception sending request to Rollbar", e);
        }
    }

}
