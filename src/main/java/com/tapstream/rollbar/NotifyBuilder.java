package com.tapstream.rollbar;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Map.Entry;

public class NotifyBuilder {

    private static final String NOTIFIER_VERSION = "1.0";

    private static final String PERSON_EMAIL_KEY = "person.email";
    private static final String PERSON_USERNAME_KEY = "person.username";
    private static final String PERSON_ID_KEY = "person.id";
    private static final String UUID_KEY = "uuid";

    private final String accessToken;
    private final String environment;

    private final JsonObject notifierData;
    private final JsonObject serverData;

    public NotifyBuilder(String accessToken, String environment)
    {
        this.accessToken = accessToken;
        this.environment = environment;
        this.notifierData = getNotifierData();
        this.serverData = getServerData();
    }

    private String getValue(String key, Map<String, String> context, String defaultValue)
    {
        if (context == null)
        {
            return defaultValue;
        }
        Object value = context.get(key);
        if (value == null)
        {
            return defaultValue;
        }
        return value.toString();
    }

    public JsonObject build(String level, String message, Throwable throwable, Map<String, String> context)
    {

        JsonObject payload = new JsonObject();

        // access token
        payload.addProperty("access_token", this.accessToken);

        // data
        JsonObject data = new JsonObject();

        // general values
        data.addProperty("environment", this.environment);
        data.addProperty("level", level);
        data.addProperty("platform", getValue("platform", context, "java"));
        data.addProperty("framework", getValue("framework", context, "java"));
        data.addProperty("language", "java");
        data.addProperty("timestamp", System.currentTimeMillis() / 1000);
        data.add("body", getBody(message, throwable));
        data.add("request", buildRequest(context));

        int length = 99;
        if (message.length() < length)
        {
            length = message.length();
        }
        data.addProperty("title", message.substring(0, length));

        // Add person if available
        JsonObject person = buildPerson(context);
        if (person != null)
        {
            data.add("person", person);
        }

        // UUID if available
        if (context.containsKey(UUID_KEY))
        {
            data.addProperty("uuid", context.get(UUID_KEY));
        }

        // Custom data and log message if there's a throwable
        JsonObject customData = buildCustom(context);
        if (throwable != null && message != null)
        {
            customData.addProperty("log", message);
        }

        data.add("custom", customData);
        data.add("client", buildClient(context));
        if (serverData != null)
        {
            data.add("server", serverData);
        }
        data.add("notifier", notifierData);
        payload.add("data", data);

        return payload;
    }

    private JsonObject buildClient(Map<String, String> ctx)
    {
        JsonObject client = new JsonObject();
        JsonObject javaScript = new JsonObject();
        javaScript.addProperty("browser", ctx.get(RollbarFilter.REQUEST_USER_AGENT));
        client.add("javascript", javaScript);
        return client;
    }

    private JsonObject buildCustom(Map<String, String> ctx)
    {
        JsonObject custom = new JsonObject();
        for (Entry<String, String> ctxEntry : ctx.entrySet())
        {
            String key = ctxEntry.getKey();
            if (key.startsWith(RollbarFilter.REQUEST_PREFIX))
            {
                continue;
            }
            custom.addProperty(key, ctxEntry.getValue());
        }
        return custom;
    }

    private JsonObject buildPerson(Map<String, String> ctx)
    {
        JsonObject request = new JsonObject();
        boolean populated = false;

        if (ctx.containsKey(PERSON_ID_KEY))
        {
            request.addProperty("id", ctx.get(PERSON_ID_KEY));
            populated = true;
        }
        if (ctx.containsKey(PERSON_USERNAME_KEY))
        {
            request.addProperty("username", ctx.get(PERSON_USERNAME_KEY));
            populated = true;
        }
        if (ctx.containsKey(PERSON_EMAIL_KEY))
        {
            request.addProperty("email", ctx.get(PERSON_EMAIL_KEY));
            populated = true;
        }

        return populated ? request : null;
    }

    private String stripPrefix(String value, String prefix)
    {
        return value.substring(prefix.length(), value.length());
    }

    private JsonObject buildRequest(Map<String, String> ctx)
    {
        JsonObject request = new JsonObject();
        request.addProperty("url", ctx.get(RollbarFilter.REQUEST_URL));
        request.addProperty("query_string", ctx.get(RollbarFilter.REQUEST_QS));

        JsonObject headers = new JsonObject();
        JsonObject params = new JsonObject();

        for (Entry<String, String> ctxEntry : ctx.entrySet())
        {
            String key = ctxEntry.getKey();
            if (key.startsWith(RollbarFilter.REQUEST_HEADER_PREFIX))
            {
                headers.addProperty(stripPrefix(key, RollbarFilter.REQUEST_HEADER_PREFIX), ctxEntry.getValue());
            } else if (key.startsWith(RollbarFilter.REQUEST_PARAM_PREFIX))
            {
                params.addProperty(stripPrefix(key, RollbarFilter.REQUEST_PARAM_PREFIX), ctxEntry.getValue());
            }
        }

        request.add("headers", headers);

        String method = ctx.get(RollbarFilter.REQUEST_METHOD);
        if (method != null)
        {
            request.addProperty("method", method);
            switch (method)
            {
                case "GET":
                    request.add("GET", params);
                    break;
                case "POST":
                    request.add("POST", params);
                    break;
            }
        }

        request.addProperty("user_ip", ctx.get(RollbarFilter.REQUEST_REMOTE_ADDR));
        return request;
    }

    private JsonObject getBody(String message, Throwable original)
    {
        JsonObject body = new JsonObject();

        Throwable throwable = original;

        JsonArray traces = new JsonArray();
        if (throwable != null)
        {
            do
            {
                traces.add(createTrace(throwable));
                throwable = throwable.getCause();
            } while (throwable != null);

            body.add("trace_chain", traces);
        }

        if (original == null && message != null)
        {
            JsonObject messageBody = new JsonObject();
            messageBody.addProperty("body", message);
            body.add("message", messageBody);
        }

        return body;
    }

    private JsonObject getNotifierData()
    {
        JsonObject notifier = new JsonObject();
        notifier.addProperty("name", "rollbar-java");
        notifier.addProperty("version", NOTIFIER_VERSION);
        return notifier;
    }

    private JsonObject getServerData()
    {
        try
        {
            InetAddress localhost = InetAddress.getLocalHost();

            String host = localhost.getHostName();
            String ip = localhost.getHostAddress();

            JsonObject notifier = new JsonObject();
            notifier.addProperty("host", host);
            notifier.addProperty("ip", ip);
            return notifier;
        } catch (UnknownHostException e)
        {
            return null;
        }
    }

    private JsonObject createTrace(Throwable throwable)
    {
        JsonObject trace = new JsonObject();

        JsonArray frames = new JsonArray();

        StackTraceElement[] elements = throwable.getStackTrace();
        for (int i = elements.length - 1; i >= 0; --i)
        {
            StackTraceElement element = elements[i];

            JsonObject frame = new JsonObject();

            frame.addProperty("class_name", element.getClassName());
            frame.addProperty("filename", element.getFileName());
            frame.addProperty("method", element.getMethodName());

            if (element.getLineNumber() > 0)
            {
                frame.addProperty("lineno", element.getLineNumber());
            }

            frames.add(frame);
        }

        JsonObject exceptionData = new JsonObject();
        exceptionData.addProperty("class", throwable.getClass().getName());
        exceptionData.addProperty("message", throwable.getMessage());

        trace.add("frames", frames);
        trace.add("exception", exceptionData);

        return trace;
    }

}
