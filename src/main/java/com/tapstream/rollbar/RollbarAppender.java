package com.tapstream.rollbar;

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
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;

@Plugin(name = "Rollbar", category = "Core", elementType = "appender", printObject = true)
public class RollbarAppender extends AbstractAppender
{

    private static final String ENV_VAR_APIKEY = "ROLLBAR_LOGBACK_API_KEY";

    private NotifyBuilder payloadBuilder;
    
    private URL url;
    private String apiKey;
    private String environment;
    private String rollbarContext;
    private String system;
    private IHttpRequester httpRequester = new HttpRequester();

    private RollbarAppender(String name, Layout layout, Filter filter, String url, String apiKey, String environment, String system){
       super(name, filter, layout);

        setUrl(url);
        setApiKey(apiKey);
        setEnvironment(environment);
        setSystem(system);

    }

    @PluginFactory
    public static RollbarAppender createAppender( @PluginAttribute("name") String name,
                @PluginElement("Layout") Layout layout,
               @PluginElement("Filters") Filter filter,
               @PluginAttribute("url") String url,
               @PluginAttribute("apikey") String apiKey,
               @PluginAttribute("environment") String env,
               @PluginAttribute("system") String system
    ){

        if(name == null) {
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

            if (apiKey == null)
            {
                LOGGER.error("Key is required in order to use Rollbar. Please get your key here https://rollbar.com");
            }

            if (env == null || env.trim().equals(""))
            {
                LOGGER.error("Please provide the environment is required.");
            }


            return new RollbarAppender(name, layout, filter, url, apiKey, env, system);
        }
    }
    
    public String getSystem()
    {
        return system;
    }

    public void setSystem(String system)
    {
        this.system = system;
    }
    
    public void setHttpRequester(IHttpRequester httpRequester){
        this.httpRequester = httpRequester;
    }
 
    public void setUrl(String url) {
        try {
            this.url = new URL(url);
        } catch (MalformedURLException e) {
            LOGGER.error("Error setting url", e);
        }
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public void setRollbarContext(String context){
        this.rollbarContext = context;
    }

    @Override
    public void append(LogEvent logEvent)
    {
        String levelName = logEvent.getLevel().toString().toLowerCase();
        String message = logEvent.getMessage().getFormattedMessage();
        Map<String, String> propertyMap = ThreadContext.getContext();

        Throwable throwable = null;
        ThrowableProxy throwableProxy = logEvent.getThrownProxy();
        if (throwableProxy != null)
        {
            throwable = throwableProxy.getThrowable();
        }

        final JSONObject payload = payloadBuilder.build(levelName, message, throwable, propertyMap);
        final HttpRequest request = new HttpRequest(url, "POST");
        request.setHeader("Content-Type", "application/json");
        request.setHeader("Accept", "application/json");
        request.setBody(payload.toString());

        sendRequest(request);

    }

    @Override
    public void start() {
        boolean error = false;

        try {
            String environmentApiKey = System.getenv(ENV_VAR_APIKEY);
            if(environmentApiKey != null){
                this.apiKey = environmentApiKey;
            }
        } catch(SecurityException e){
            LOGGER.warn("Access to environment variables was denied. ("+e.getMessage()+")");
        }

        if (this.url == null) {
            LOGGER.error("No url set for the appender named [" + getName() + "].");
            error = true;
        }
        if (this.apiKey == null || this.apiKey.isEmpty()) {
            LOGGER.error("No apiKey set for the appender named [" + getName() + "].");
            error = true;
        }
        if (this.environment == null || this.environment.isEmpty()) {
            LOGGER.error("No environment set for the appender named [" + getName() + "].");
            error = true;
        }

        try {
            payloadBuilder = new NotifyBuilder(apiKey, environment, rollbarContext);
        } catch (JSONException e) {
            LOGGER.error("Error building NotifyBuilder", e);
            error = true;
        }

        if (!error){
            super.start();
        }

    }

    @Override
    public void stop() {
        super.stop();
    }

    private void sendRequest(HttpRequest request){
        try {
            int statusCode = httpRequester.send(request);
            if (statusCode >= 200 && statusCode <= 299){
                // Everything went OK
            } else {
                LOGGER.error("Non-2xx response from Rollbar: " + statusCode);
            }

        } catch (IOException e) {
            LOGGER.error("Exception sending request to Rollbar", e);
        }
    }

}
